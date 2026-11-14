package kr.ac.sejong.parking.zone.adapter.sse

import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.*
import jakarta.servlet.http.*
import kr.ac.sejong.parking.auth.*
import kr.ac.sejong.parking.web.ApiProblem
import kr.ac.sejong.parking.zone.application.ReadQueries
import org.springframework.scheduling.annotation.Scheduled
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Single-instance, bounded, latest-state delivery. Poll only committed consistent DB reads. */
class ZoneStreams(private val queries: ReadQueries, private val users: JdbcWebUsers, private val lifetime: SessionLifetime): AutoCloseable {
    private class Connection(val id:String,val zone:String,val user:String,val session:HttpSession,val sessionId:String,val output:NonBlockingSse) {
        val polling=AtomicBoolean()
        @Volatile var checkedAt=System.nanoTime()
        @Volatile var sentAt=System.nanoTime()
        @Volatile var revision=-1L
    }
    private val streams=ConcurrentHashMap<String,Connection>()
    private val workers=ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(100),
        ThreadFactory { task -> Thread(task,"zone-stream-reader").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())

    @Synchronized fun open(zone:String,user:String,session:HttpSession,request:HttpServletRequest,response:HttpServletResponse,initial:JsonNode) {
        if(!lifetime.valid(session)) throw ApiProblem(401,"UNAUTHENTICATED","다시 로그인해 주세요.")
        val sid=session.id
        if(streams.size >= 50 || streams.values.count { it.sessionId == sid } >= 3)
            throw ApiProblem(429,"TOO_MANY_REQUESTS","열려 있는 실시간 화면이 많습니다.",10)
        response.contentType="text/event-stream"; response.characterEncoding="UTF-8"
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no")
        val async=request.startAsync();async.timeout=0
        val id=UUID.randomUUID().toString()
        val output=response.outputStream
        val writer=NonBlockingSse(output,{
            streams.remove(id)
            try{async.complete()}catch(_:IllegalStateException){}
        })
        val connection=Connection(id,zone,user,session,sid,writer)
        streams[id]=connection
        async.addListener(object:AsyncListener {
            override fun onComplete(event:AsyncEvent){writer.close()}
            override fun onTimeout(event:AsyncEvent){writer.close()}
            override fun onError(event:AsyncEvent){writer.close()}
            override fun onStartAsync(event:AsyncEvent){}
        })
        try { output.setWriteListener(writer); send(connection,initial) } catch(error:Exception) { writer.close();throw error }
    }
    private fun send(c:Connection,body:JsonNode) {
        if(c.output.isClosed || !lifetime.valid(c.session) || System.nanoTime()-c.checkedAt > 15_000_000_000L) {c.output.close();return}
        val revision=body["view_revision"].longValue()
        if(revision>c.revision) {
            c.revision=revision
            c.output.offer("id: $revision\nevent: zone_snapshot\ndata: $body\n\n".toByteArray(Charsets.UTF_8))
            c.sentAt=System.nanoTime()
        } else if(System.nanoTime()-c.sentAt >= 15_000_000_000L) {
            c.output.offer(": keepalive\n\n".toByteArray());c.sentAt=System.nanoTime()
        }
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    fun tick() {
        for(c in streams.values) {
            if(c.output.stalled() || !lifetime.valid(c.session) || System.nanoTime()-c.checkedAt>15_000_000_000L) {c.output.close();continue}
            if(!c.polling.compareAndSet(false,true)) continue
            try { workers.execute {
                try {
                    if(c.output.isClosed || !lifetime.valid(c.session) || users.activeIdentity(c.user)==null) {c.output.close();return@execute}
                    val body=queries.zone(c.user,c.zone)
                    c.checkedAt=System.nanoTime()
                    send(c,body)
                } catch(_:Exception) {c.output.close()} finally {c.polling.set(false)}
            }} catch(_:RejectedExecutionException){c.polling.set(false);c.output.close()}
        }
    }
    fun closeSession(id:String) {streams.values.filter {it.sessionId==id}.forEach {it.output.close()}}
    fun activeCount()=streams.size
    override fun close(){streams.values.forEach {it.output.close()};workers.shutdownNow()}
}
