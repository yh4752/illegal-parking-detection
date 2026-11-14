package kr.ac.sejong.parking.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.*
import kr.ac.sejong.parking.auth.SessionLifetime
import kr.ac.sejong.parking.device.adapter.jdbc.*
import kr.ac.sejong.parking.device.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("database")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("web-api")
@AutoConfigureMockMvc
@Import(WebReadDatabaseTest.TimeConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebReadDatabaseTest(
    @Autowired val mvc:MockMvc, @Autowired val jdbc:JdbcTemplate, @Autowired val mapper:ObjectMapper,
    @Autowired val clock:WebTestClock, @Autowired val manager:PlatformTransactionManager,
    @Autowired val revisions:RevisionReservations, @Autowired val encoder:PasswordEncoder,
) {
    @TestConfiguration class TimeConfig { @Bean @Primary fun testClock()=WebTestClock() }
    companion object {
        private val schema="it_${UUID.randomUUID().toString().replace("-","")}"
        private val files=Files.createTempDirectory("parking-web-test-")
        @JvmStatic @DynamicPropertySource fun properties(r:DynamicPropertyRegistry) {
            r.add("spring.flyway.default-schema") { schema };r.add("spring.datasource.hikari.schema") { schema }
            r.add("parking.revision.primary") { files.resolve("primary/manifest.json").toString() }
            r.add("parking.revision.backup") { files.resolve("backup/manifest.json").toString() }
            r.add("PARKING_CURSOR_HMAC_KEY") { java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 17 }) }
        }
    }
    @LocalServerPort var port:Int=0
    private val password="test-only-password-14"
    private val examples by lazy { javaClass.getResourceAsStream("/contracts/examples.json").use { mapper.readTree(it) } }
    private val schemaDocument by lazy { javaClass.getResourceAsStream("/contracts/effective/parking.schema.json").use { mapper.readTree(it) } }
    private val store by lazy { EventTransactionStore(jdbc,TransactionTemplate(manager),revisions,mapper,clock) }
    private val service by lazy { EventIngestionService(EventMessageValidator(mapper) { _,_->true },EventCanonicalizer(mapper),store) }
    private fun body(r:MvcResult):JsonNode = mapper.readTree(r.response.contentAsByteArray)
    private fun checkSchema(name:String,node:JsonNode) {
        val schema=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(mapper.createObjectNode().apply {
            put("\$schema",schemaDocument["\$schema"].asText());put("\$ref","#/\$defs/$name");set<JsonNode>("\$defs",schemaDocument["\$defs"])
        },SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build())
        assertThat(schema.validate(node)).describedAs("$name: $node").isEmpty()
    }
    private fun csrf(session:MockHttpSession?=null):Pair<MockHttpSession,String> {
        val req=get("/api/csrf");session?.let { req.session(it) }
        val r=mvc.perform(req).andReturn();assertThat(r.response.status).isEqualTo(200);checkSchema("CsrfResponse",body(r))
        return Pair(r.request.getSession(false) as MockHttpSession,body(r)["token"].asText())
    }
    private fun login(name:String="viewer-a"):MockHttpSession {
        val (session,token)=csrf();val r=mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",token)
            .contentType("application/json").content(mapper.writeValueAsBytes(mapOf("username" to name,"password" to password)))).andReturn()
        assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(200);checkSchema("AuthUser",body(r))
        return r.request.getSession(false) as MockHttpSession
    }
    private fun getAs(path:String,session:MockHttpSession)=mvc.perform(get(path).session(session)).andReturn()
    private fun error(r:MvcResult,status:Int,code:String) {
        assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(status)
        checkSchema("RestError",body(r));assertThat(body(r)["code"].asText()).isEqualTo(code)
        assertThat(r.response.getHeader("Cache-Control")).contains("no-store")
    }
    @BeforeAll fun register() {
        val hash=encoder.encode(password)
        for ((id,role) in listOf("viewer-a" to "VIEWER","viewer-b" to "VIEWER","operator" to "OPERATOR"))
            jdbc.update("INSERT INTO app_user(user_id,username,password_hash,role) VALUES (?,?,?,?)",id,id,hash,role)
        for (n in 1..2) {
            val zone="zone-0$n";val device="edge-0$n"
            jdbc.update("INSERT INTO zone(zone_id,display_name) VALUES (?,?)",zone,zone)
            jdbc.update("INSERT INTO device(device_id,zone_id,https_token_sha256) VALUES (?,?,?)",device,zone,"0".repeat(64))
            jdbc.update("INSERT INTO zone_view(zone_id,device_id) VALUES (?,?)",zone,device)
            val policy=examples["config_desired"]["desired_policy"].deepCopy<ObjectNode>().apply { put("zone_id",zone) }
            jdbc.update("INSERT INTO zone_policy(zone_id,version,base_version,change_class,content,content_sha256,created_by,reason) VALUES (?,3,2,'TIMING',?::jsonb,?,'operator','test')",zone,mapper.writeValueAsString(policy),EventCanonicalizer(mapper).hash(policy))
            jdbc.update("UPDATE zone SET latest_policy_version=3 WHERE zone_id=?",zone)
            revisions.withZone(zone) { }
        }
    }
    @BeforeEach fun reset() {
        clock.now=Instant.parse("2026-11-14T00:00:00Z")
        jdbc.execute("TRUNCATE incident,inbound_receipt,inbound_quarantine,command,review,device_image,entity_tombstone,pause_projection,audit_entry CASCADE")
        jdbc.update("UPDATE zone_view SET recovery_hold=false,snapshot=null,current_event_id=null,received_at=null,last_device_seq=-1,view_revision=0,last_heartbeat_at=null,connection_state='OFFLINE'")
        jdbc.update("UPDATE app_user SET enabled=true")
        jdbc.execute("DELETE FROM user_zone_access")
        jdbc.execute("INSERT INTO user_zone_access VALUES ('viewer-a','zone-01'),('operator','zone-01'),('viewer-b','zone-02')")
    }
    @AfterAll fun clean() { jdbc.execute("DROP SCHEMA $schema CASCADE");revisions.close();files.toFile().deleteRecursively() }
    private fun ingest(id:String="message-1",seq:Long=10,event:String="evt-001"):JsonNode {
        val node=examples["guidance_complete"].deepCopy<ObjectNode>().apply {
            put("message_id",id);put("device_seq",seq);put("event_id",event)
            (get("event") as ObjectNode).put("event_id",event)
            (get("snapshot") as ObjectNode).put("device_seq",seq).put("current_event_id",event)
        }
        assertThat(service.receive("parking/v2/devices/edge-01/events",mapper.writeValueAsBytes(node)))
            .isInstanceOfSatisfying(EventIngestResult.Committed::class.java) { assertThat(it.status).describedAs(it.toString()).isEqualTo("STORED") }
        return node
    }

    @Test fun `CSRF 로그인 회전과 로그아웃은 실제 필터에서 동작한다`() {
        error(mvc.perform(post("/api/auth/login").contentType("application/json").content("{}" )).andReturn(),403,"FORBIDDEN")
        val (session,oldToken)=csrf();val oldId=session.id
        val r=mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",oldToken).contentType("application/json")
            .content(mapper.writeValueAsBytes(mapOf("username" to "viewer-a","password" to password)))).andReturn()
        assertThat(r.response.status).isEqualTo(200);assertThat(session.id).isNotEqualTo(oldId)
        assertThat(getAs("/api/auth/me",session).response.status).isEqualTo(200)
        error(mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN",oldToken)).andReturn(),403,"FORBIDDEN")
        val (_,newToken)=csrf(session)
        assertThat(mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN",newToken)).andReturn().response.status).isEqualTo(204)
        assertThat(session.isInvalid).isTrue()
        error(mvc.perform(get("/api/auth/me")).andReturn(),401,"UNAUTHENTICATED")
    }
    @Test fun `빈 상태 여덟 키를 유지하고 두 사용자 구역 접근과 미구현 제어를 차단한다`() {
        val a=login();val b=login("viewer-b");val op=login("operator")
        val empty=getAs("/api/zones/zone-01/status",a);checkSchema("ZoneView",body(empty))
        assertThat(body(empty)["snapshot"].isNull).isTrue();assertThat(body(empty).size()).isEqualTo(8)
        error(getAs("/api/zones/zone-02/status",a),403,"FORBIDDEN")
        error(getAs("/api/zones/zone-01/status",b),403,"FORBIDDEN")
        error(getAs("/api/events?zone_id=zone-02",op),403,"FORBIDDEN")
        error(getAs("/api/commands/no-command",op),403,"FORBIDDEN")
        error(getAs("/actuator/env",op),403,"FORBIDDEN")
        error(getAs("/api/zones/missing/status",a),404,"NOT_FOUND")
    }
    @Test fun `저장 서비스가 커밋한 사건을 HTTP 목록 상세 현재 상태에서 같은 계약으로 읽는다`() {
        ingest("seq12",12);ingest("seq11",11)
        val session=login()
        val zone=body(getAs("/api/zones/zone-01/status",session));checkSchema("ZoneView",zone)
        assertThat(zone["snapshot"]["device_seq"].asLong()).isEqualTo(12)
        val list=body(getAs("/api/events?zone_id=zone-01",session));checkSchema("EventListResponse",list)
        assertThat(list["items"].size()).isEqualTo(1)
        val detail=body(getAs("/api/events/evt-001",session));checkSchema("EventDetail",detail)
        assertThat(detail["last_device_seq"].asLong()).isEqualTo(12)
        assertThat(detail["messages"].map { it["device_seq"].asLong() }).containsExactly(11L,12L)
    }
    @Test fun `계정 비활성 권한 회수와 역할 변경은 다음 요청에 반영한다`() {
        val a=login();jdbc.update("DELETE FROM user_zone_access WHERE user_id='viewer-a'")
        error(getAs("/api/zones/zone-01/status",a),403,"FORBIDDEN")
        jdbc.update("UPDATE app_user SET role='OPERATOR' WHERE user_id='viewer-a'")
        assertThat(body(getAs("/api/auth/me",a))["role"].asText()).isEqualTo("OPERATOR")
        jdbc.update("UPDATE app_user SET enabled=false WHERE user_id='viewer-a'")
        error(getAs("/api/auth/me",a),401,"UNAUTHENTICATED");assertThat(a.isInvalid).isTrue()
        jdbc.update("UPDATE app_user SET role='VIEWER' WHERE user_id='viewer-a'")
    }
    @Test fun `idle 및 absolute 세션 만료가 HTTP에서 적용된다`() {
        val a=login();clock.advance(Duration.ofMinutes(30));error(getAs("/api/auth/me",a),401,"UNAUTHENTICATED")
        val b=login();b.setAttribute(SessionLifetime.STARTED,clock.millis()-Duration.ofHours(8).toMillis())
        error(getAs("/api/auth/me",b),401,"UNAUTHENTICATED")
    }
    @Test fun `복구 hold는 모든 자료 조회를 503으로 막지만 로그인은 허용한다`() {
        ingest();jdbc.update("UPDATE zone_view SET recovery_hold=true WHERE zone_id='zone-01'")
        val a=login()
        for(path in listOf("/api/zones/zone-01/status","/api/events?zone_id=zone-01","/api/events/evt-001"))
            error(getAs(path,a),503,"SERVICE_UNAVAILABLE")
        assertThat(getAs("/api/auth/me",a).response.status).isEqualTo(200)
    }
    @Test fun `목록 동일 시각 순서 필터 범위와 서명 cursor를 검증한다`() {
        ingest("ma",10,"a");ingest("mb",11,"b");clock.advance(Duration.ofSeconds(1));ingest("mc",12,"c")
        val a=login();val first=body(getAs("/api/events?zone_id=zone-01&limit=1",a))
        assertThat(first["items"][0]["event_id"].asText()).isEqualTo("c")
        val second=body(getAs("/api/events?zone_id=zone-01&limit=1&cursor=${first["next_cursor"].asText()}",a))
        assertThat(second["items"][0]["event_id"].asText()).isEqualTo("b")
        val third=body(getAs("/api/events?zone_id=zone-01&limit=1&cursor=${second["next_cursor"].asText()}",a))
        assertThat(third["items"][0]["event_id"].asText()).isEqualTo("a");assertThat(third["next_cursor"].isNull).isTrue()
        val filtered=body(getAs("/api/events?zone_id=zone-01&from=2026-11-14T00:00:00Z&to=2026-11-14T00:00:01Z",a))
        assertThat(filtered["items"].map { it["event_id"].asText() }).containsExactly("b","a")
        error(getAs("/api/events?zone_id=zone-01&state=HELD&cursor=${first["next_cursor"].asText()}",a),400,"INVALID_SCHEMA")
        error(getAs("/api/events?zone_id=zone-01&cursor=bad",a),400,"INVALID_SCHEMA")
    }
    @Test fun `종료 요약 보관 만료 경계 tombstone 미존재와 권한 거부를 구별한다`() {
        ingest();val expiry=clock.instant().plusMillis(1)
        jdbc.update("UPDATE incident SET state='CLOSED_EXIT',close_reason='OBSERVED_EXIT',first_close_received_at=?,summary_expires_at=?",
            Timestamp.from(expiry.minus(Duration.ofDays(30))),Timestamp.from(expiry))
        val a=login();val b=login("viewer-b")
        assertThat(getAs("/api/events/evt-001",a).response.status).isEqualTo(200)
        clock.advance(Duration.ofMillis(1));error(getAs("/api/events/evt-001",a),410,"GONE")
        error(getAs("/api/events/evt-001",b),403,"FORBIDDEN")
        assertThat(body(getAs("/api/events?zone_id=zone-01",a))["items"].size()).isZero()
        assertThat(body(getAs("/api/zones/zone-01/status",a))["snapshot"].isNull).isFalse()
        jdbc.update("INSERT INTO entity_tombstone(device_id,entity_kind,entity_id,terminal_state,compacted_at) VALUES ('edge-01','EVENT','evt-001','CLOSED_EXIT',now())")
        jdbc.execute("DELETE FROM incident")
        error(getAs("/api/events/evt-001",a),410,"GONE");error(getAs("/api/events/evt-001",b),403,"FORBIDDEN")
        error(getAs("/api/events/not-known",a),404,"NOT_FOUND")
        clock.advance(Duration.ofMillis(1));error(getAs("/api/events/evt-001",a),410,"GONE")
    }
    @Test fun `원문과 리뷰는 정리 배치 전에도 삼십일 경계에서 제외한다`() {
        ingest("old",10);ingest("new",11)
        val cutoff=Timestamp.from(clock.instant().minus(Duration.ofDays(30)))
        jdbc.update("UPDATE event_message SET received_at=? WHERE message_id='old'",cutoff)
        jdbc.update("INSERT INTO review(review_id,event_id,zone_id,created_by,created_at,note) VALUES ('old','evt-001','zone-01','operator',?,'old')",cutoff)
        jdbc.update("INSERT INTO review(review_id,event_id,zone_id,created_by,created_at,note) VALUES ('new','evt-001','zone-01','operator',?,'new')",Timestamp.from(clock.instant()))
        val detail=body(getAs("/api/events/evt-001",login()));checkSchema("EventDetail",detail)
        assertThat(detail["messages"].map { it["message_id"].asText() }).containsExactly("new")
        assertThat(detail["reviews"].map { it["review_id"].asText() }).containsExactly("new")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_message",Long::class.java)).isEqualTo(2)
    }
    @Test fun `저장된 명령과 사진을 조회하고 OPERATOR에게도 확인 토큰을 숨긴다`() {
        ingest();val now=Timestamp.from(clock.instant())
        repeat(22) { n -> jdbc.update("""INSERT INTO command(command_id,device_id,zone_id,type,requested_by,wire_sha256,server_status,issued_at,expires_at,result_received_at,result,result_data,first_terminal_at)
            VALUES (?,'edge-01','zone-01','REQUEST_SNAPSHOT','operator',?,'APPLIED',?,?::timestamptz+interval '60 seconds',?,'APPLIED',?::jsonb,?)""",
            "cmd-%02d".format(n),"a".repeat(64),now,now,now,"""{"pause_ids":[],"image_id":null,"confirmation_token":"secret-never-expose","token_valid_for_ms":30000,"closed_event_id":null}""",now) }
        repeat(11) { n -> jdbc.update("""INSERT INTO device_image(image_id,device_id,zone_id,event_id,image_kind,status,first_received_at)
            VALUES (?,'edge-01','zone-01','evt-001','VIEW','PENDING',?)""","img-%02d".format(n),now) }
        jdbc.update("UPDATE device_image SET expires_at=? WHERE image_id='img-10'",now)
        val zone=body(getAs("/api/zones/zone-01/status",login("operator")));checkSchema("ZoneView",zone)
        assertThat(zone["commands"].size()).isEqualTo(20);assertThat(zone["image_refs"].size()).isEqualTo(10)
        assertThat(zone["image_refs"][0]["status"].asText()).isEqualTo("EXPIRED")
        assertThat(zone["image_refs"][0]["error_code"].asText()).isEqualTo("RETENTION_EXPIRED")
        assertThat(zone.toString()).doesNotContain("secret-never-expose")
        assertThat(zone["commands"].all { it["result_data"].has("confirmation_token") && it["result_data"]["confirmation_token"].isNull }).isTrue()
        val detail=body(getAs("/api/events/evt-001",login()));assertThat(detail["images"].size()).isEqualTo(11);checkSchema("EventDetail",detail)
    }
    @Test fun `동시 쓰기 중 revision과 snapshot 순번을 서로 다른 시점으로 읽지 않는다`() {
        val original=ingest();val a=login();val executor=Executors.newSingleThreadExecutor()
        val running=java.util.concurrent.atomic.AtomicBoolean(true)
        val started=java.util.concurrent.CountDownLatch(1)
        val writes=java.util.concurrent.atomic.AtomicInteger()
        val writer=executor.submit {
            while (running.get()) {
                val seq=writes.incrementAndGet().toLong()+100
                val snapshot=original["snapshot"].deepCopy<ObjectNode>().put("device_seq",seq)
                jdbc.update("UPDATE zone_view SET view_revision=?,last_device_seq=?,snapshot=?::jsonb WHERE zone_id='zone-01'",seq,seq,mapper.writeValueAsString(snapshot))
                started.countDown()
            }
        }
        try {
            assertThat(started.await(10,TimeUnit.SECONDS)).isTrue()
            repeat(30) {
                val result=getAs("/api/zones/zone-01/status",a)
                assertThat(result.response.status).isEqualTo(200)
                val row=body(result)
                assertThat(row["view_revision"].asLong()).isEqualTo(row["snapshot"]["device_seq"].asLong())
            }
            assertThat(writes.get()).isGreaterThan(1)
        } finally { running.set(false);try { writer.get(10,TimeUnit.SECONDS) } finally { executor.shutdownNow() } }
    }

    @Test fun `중복 JSON키 추가키 잘못된 JSON과 길이 제한은 인증 이전에 거부한다`() {
        val (session,token)=csrf()
        for (raw in listOf("{","{\"username\":\"x\",\"username\":\"y\",\"password\":\"z\"}","{\"username\":\"x\",\"password\":\"y\",\"extra\":1}"," ".repeat(65537)))
            error(mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(raw)).andReturn(),400,"INVALID_SCHEMA")
    }
    @Test fun `로그인 실패 제한은 429와 RetryAfter를 반환한다`() {
        val (session,token)=csrf()
        repeat(5) { error(mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",token).contentType("application/json")
            .content("{\"username\":\"not-a-user\",\"password\":\"wrong\"}")).andReturn(),401,"UNAUTHENTICATED") }
        val limited=mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",token).contentType("application/json")
            .content("{\"username\":\"not-a-user\",\"password\":\"wrong\"}")).andReturn()
        error(limited,429,"RATE_LIMITED");assertThat(limited.response.getHeader("Retry-After")).isEqualTo("300")
    }

    @Test fun `DB 조회와 인증 저장소 오류는 비밀정보 없이 503으로 응답한다`() {
        val a=login()
        jdbc.execute("ALTER TABLE incident RENAME TO incident_unavailable")
        try {
            val result=getAs("/api/events?zone_id=zone-01",a)
            error(result,503,"SERVICE_UNAVAILABLE")
            assertThat(result.response.contentAsString).doesNotContain("SELECT","incident_unavailable",password)
        } finally { jdbc.execute("ALTER TABLE incident_unavailable RENAME TO incident") }
        val (session,token)=csrf()
        jdbc.execute("ALTER TABLE app_user RENAME TO app_user_unavailable")
        try {
            error(getAs("/api/auth/me",a),503,"SERVICE_UNAVAILABLE")
            repeat(6) {
                error(mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",token)
                    .contentType("application/json").content(mapper.writeValueAsBytes(mapOf("username" to "viewer-a","password" to password))))
                    .andReturn(),503,"SERVICE_UNAVAILABLE")
            }
        } finally { jdbc.execute("ALTER TABLE app_user_unavailable RENAME TO app_user") }
        assertThat(getAs("/api/auth/me",a).response.status).isEqualTo(200)
        login()
    }
    @Test fun `DB 예약 범위와 파일이 다르면 읽기를 막고 정상 범위에서 복구된다`() {
        val a=login()
        val reserved=jdbc.queryForObject("SELECT revision_range_end FROM zone_view WHERE zone_id='zone-01'",Long::class.java)!!
        jdbc.update("UPDATE zone_view SET revision_range_end=? WHERE zone_id='zone-01'",reserved+1_000_000)
        try { error(getAs("/api/zones/zone-01/status",a),503,"SERVICE_UNAVAILABLE") }
        finally { jdbc.update("UPDATE zone_view SET revision_range_end=? WHERE zone_id='zone-01'",reserved) }
        assertThat(getAs("/api/zones/zone-01/status",a).response.status).isEqualTo(200)
    }
    @Test fun `계정 CLI 별도 프로세스가 생성 부여 회수 비활성과 감사를 실제 DB에 반영한다`() {
        val secret=files.resolve("account-secret")
        Files.writeString(secret,password)
        Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        fun invoke(vararg args:String):String {
            val output=Files.createTempFile(files,"account-cli-",".log")
            val process=ProcessBuilder(listOf(System.getProperty("java.home")+"/bin/java","-cp",
                System.getProperty("parking.test.runtimeClasspath"),"kr.ac.sejong.parkingtools.WebAccountCliKt")+args)
                .redirectErrorStream(true).redirectOutput(output.toFile()).apply {
                    environment()["PARKING_WEB_PASSWORD_FILE"]=secret.toString()
                    environment()["SPRING_DATASOURCE_HIKARI_SCHEMA"]=schema
                }.start()
            try {
                assertThat(process.waitFor(30,TimeUnit.SECONDS)).describedAs("CLI completion").isTrue()
                val text=Files.readString(output)
                assertThat(process.exitValue()).describedAs(text).isZero()
                assertThat(text).doesNotContain(password,"Tomcat started","MQTT_","$"+"2a$")
                return text
            } finally { process.destroyForcibly();Files.deleteIfExists(output) }
        }
        try {
            invoke("create-user","--username=cli-user","--role=VIEWER","--zone-id=zone-01")
            val row=jdbc.queryForMap("SELECT * FROM app_user WHERE username='cli-user'")
            val id=row["user_id"].toString()
            assertThat(encoder.matches(password,row["password_hash"].toString())).isTrue()
            val session=login("cli-user")
            assertThat(getAs("/api/zones/zone-01/status",session).response.status).isEqualTo(200)
            error(getAs("/api/zones/zone-02/status",session),403,"FORBIDDEN")
            invoke("grant-zone","--user-id=$id","--zone-id=zone-02")
            assertThat(getAs("/api/zones/zone-02/status",session).response.status).isEqualTo(200)
            invoke("revoke-zone","--user-id=$id","--zone-id=zone-01")
            error(getAs("/api/zones/zone-01/status",session),403,"FORBIDDEN")
            invoke("disable-user","--user-id=$id")
            error(getAs("/api/auth/me",session),401,"UNAUTHENTICATED")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry WHERE related_id=?",Long::class.java,id)).isEqualTo(4)
            assertThat(jdbc.queryForList("SELECT detail FROM audit_entry WHERE related_id=?",id).toString()).doesNotContain(password,row["password_hash"].toString())
        } finally { Files.deleteIfExists(secret) }
    }

    private fun httpSession():Pair<HttpClient,String> {
        val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val first=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString())
        val cookie=first.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
        val token=mapper.readTree(first.body())["token"].asText()
        val login=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/auth/login")).header("Cookie",cookie)
            .header("X-CSRF-TOKEN",token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("username" to "viewer-a","password" to password)))).build(),HttpResponse.BodyHandlers.ofString())
        assertThat(login.statusCode()).isEqualTo(200)
        return client to login.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
    }
    private fun httpGet(path:String,cookie:String)=HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        .header("Cookie",cookie).header("Accept",if(path.startsWith("/api/stream")) "text/event-stream" else "application/json").GET().build()
    private fun readFrame(reader:java.io.BufferedReader):String {
        val executor=Executors.newSingleThreadExecutor()
        try { return executor.submit<String> { buildString { while(true) {val line=reader.readLine()?:break; if(line.isEmpty())break;append(line).append('\n')} } }.get(5,TimeUnit.SECONDS) }
        finally {executor.shutdownNow()}
    }
    @Test fun `SSE actual HTTP sends committed full snapshots and immediately closes on logout`() {
        val (client,cookie)=httpSession()
        val response=client.send(httpGet("/api/stream?zone_id=zone-01",cookie),HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.headers().firstValue("content-type").orElseThrow()).contains("text/event-stream")
            val reader=input.bufferedReader();val first=readFrame(reader)
            assertThat(first).contains("id: 0","event: zone_snapshot")
            val firstBody=mapper.readTree(first.lineSequence().first {it.startsWith("data:")}.removePrefix("data: "))
            checkSchema("ZoneView",firstBody);assertThat(firstBody["snapshot"].isNull).isTrue()
            ingest("sse-message",10)
            val next=readFrame(reader);assertThat(next).contains("id: 1")
            val body=mapper.readTree(next.lineSequence().first {it.startsWith("data:")}.removePrefix("data: "))
            checkSchema("ZoneView",body);assertThat(body["snapshot"]["device_seq"].asLong()).isEqualTo(10)
            val token=mapper.readTree(client.send(httpGet("/api/csrf",cookie),HttpResponse.BodyHandlers.ofString()).body())["token"].asText()
            val logout=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/auth/logout")).header("Cookie",cookie).header("X-CSRF-TOKEN",token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
            assertThat(logout.statusCode()).isEqualTo(204);assertThat(readFrame(reader)).isEmpty()
        }
    }
    @Test fun `SSE closes when the zone grant is revoked and reconnect returns a JSON 403`() {
        val (client,cookie)=httpSession()
        val response=client.send(httpGet("/api/stream?zone_id=zone-01",cookie),HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            val reader=input.bufferedReader();readFrame(reader)
            jdbc.update("DELETE FROM user_zone_access WHERE user_id='viewer-a' AND zone_id='zone-01'")
            assertThat(readFrame(reader)).isEmpty()
            val denied=client.send(httpGet("/api/stream?zone_id=zone-01",cookie),HttpResponse.BodyHandlers.ofString())
            assertThat(denied.statusCode()).isEqualTo(403);checkSchema("RestError",mapper.readTree(denied.body()))
        }
    }
    @Test fun `EventSource Accept header preserves JSON 400 and 404 before the stream opens`() {
        val (client,cookie)=httpSession()
        for((path,status) in listOf("/api/stream" to 400,"/api/stream?zone_id=unknown-zone" to 404)) {
            val response=client.send(httpGet(path,cookie),HttpResponse.BodyHandlers.ofString())
            assertThat(response.statusCode()).isEqualTo(status)
            assertThat(response.headers().firstValue("content-type").orElseThrow()).contains("application/json")
            checkSchema("RestError",mapper.readTree(response.body()))
        }
    }
    @Test fun `SSE enforces per session quota and stops at idle expiry without touching the session`() {
        val (client,cookie)=httpSession()
        val opened=(1..3).map {client.send(httpGet("/api/stream?zone_id=zone-01",cookie),HttpResponse.BodyHandlers.ofInputStream())}
        try {
            opened.forEach {assertThat(it.statusCode()).isEqualTo(200)}
            val limited=client.send(httpGet("/api/stream?zone_id=zone-01",cookie),HttpResponse.BodyHandlers.ofString())
            assertThat(limited.statusCode()).isEqualTo(429);checkSchema("RestError",mapper.readTree(limited.body()));assertThat(limited.headers().firstValue("Retry-After").orElseThrow()).isEqualTo("10")
            clock.now=clock.now.plusSeconds(1800)
            for(response in opened){val reader=response.body().bufferedReader();readFrame(reader);assertThat(readFrame(reader)).isEmpty()}
        } finally {opened.forEach {it.body().close()}}
    }

    @Test fun `실제 HTTP 서버가 Secure HttpOnly SameSite 쿠키를 발급하고 세션 ID를 교체한다`() {
        val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val base="http://127.0.0.1:$port"
        val first=client.send(HttpRequest.newBuilder(URI.create("$base/api/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertThat(first.statusCode()).isEqualTo(200)
        val header=first.headers().firstValue("set-cookie").orElseThrow()
        assertThat(header).contains("HttpOnly","Secure","SameSite=Lax")
        val cookie=header.substringBefore(';');val token=mapper.readTree(first.body())["token"].asText()
        val logged=client.send(HttpRequest.newBuilder(URI.create("$base/api/auth/login")).header("Cookie",cookie)
            .header("X-CSRF-TOKEN",token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("username" to "viewer-a","password" to password)))).build(),HttpResponse.BodyHandlers.ofString())
        assertThat(logged.statusCode()).describedAs(logged.body()).isEqualTo(200)
        val newCookie=logged.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
        assertThat(newCookie).isNotEqualTo(cookie)
        val old=client.send(HttpRequest.newBuilder(URI.create("$base/api/auth/me")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertThat(old.statusCode()).isEqualTo(401)
        val active=client.send(HttpRequest.newBuilder(URI.create("$base/api/auth/me")).header("Cookie",newCookie).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertThat(active.statusCode()).isEqualTo(200)
    }
}
