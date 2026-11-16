package kr.ac.sejong.parkingtools

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionLedger
import kr.ac.sejong.parking.operations.*
import org.flywaydb.core.Flyway
import org.springframework.boot.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import javax.sql.DataSource

/** Explicit empty handoff DB only. No default application startup side effects. */
fun main(args:Array<String>) {
    require(args.contentEquals(arrayOf("--initialize-new-demo"))) { "explicit_initialize_new_demo_required" }
    val url=System.getenv("PARKING_DB_URL") ?: error("explicit_database_url_required")
    require(Regex("^jdbc:postgresql://[^/]+/parking_handoff$").matches(url)) { "dedicated_parking_handoff_database_required" }
    val app=SpringApplication(DemoInitializationContext::class.java).apply {webApplicationType=WebApplicationType.NONE;setBannerMode(Banner.Mode.OFF)}
    app.run("--spring.main.web-application-type=none","--spring.profiles.active=database","--spring.flyway.enabled=false").use { context ->
        val jdbc=context.getBean(JdbcTemplate::class.java)
        check(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_type='BASE TABLE'",Long::class.java)==0L) { "database_must_be_empty_do_not_reset_existing_data" }
        val ds=context.getBean(DataSource::class.java)
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        for(name in listOf("register-local-device.sql","register-local-policy.sql")) {
            val script=ClassPathResource("demo/$name").inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            jdbc.execute(script)
        }
        val mapper=context.getBean(ObjectMapper::class.java)
        val tx=TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
        val request=DemoInitializationRequest("zone-01","edge-01",3,"가상 장치 프론트 인계 환경 신규 생성")
        val initializer=FreshDemoInitializer(jdbc,tx,mapper);initializer.inspect(request)
        RevisionReservations(jdbc,tx,mapper,Path.of(System.getenv("PARKING_REVISION_PRIMARY")),Path.of(System.getenv("PARKING_REVISION_BACKUP")), RevisionLedger.configured(context.environment, mapper)).use {
            it.initialize();initializer.apply(request,it)
        }
        println("HANDOFF_DATABASE_INITIALIZED zone_id=zone-01 virtual_device=true")
    }
}
