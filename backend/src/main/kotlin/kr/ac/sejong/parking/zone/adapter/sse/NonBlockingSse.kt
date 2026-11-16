package kr.ac.sejong.parking.zone.adapter.sse

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import java.util.concurrent.atomic.AtomicBoolean

/** The servlet contract permits write only while isReady. One current frame + latest pending frame. */
class NonBlockingSse(
    private val output: ServletOutputStream,
    private val finish: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onSlowClose: () -> Unit = {},
) : WriteListener {
    private var frame: ByteArray? = null
    private var offset = 0
    private var latest: ByteArray? = null
    private var progressAt = nanoTime()
    private var dirty = false
    private val closed = AtomicBoolean()
    val isClosed get() = closed.get()
    @Synchronized fun offer(bytes: ByteArray) {
        if(isClosed) return
        if(bytes.size > 1_048_576) { close(); return }
        if(frame == null && !dirty) { frame=bytes; offset=0; progressAt=nanoTime() } else latest=bytes
        drain()
    }
    @Synchronized fun stalled(): Boolean = !isClosed && (frame != null || dirty) && nanoTime()-progressAt > 5_000_000_000L
    @Synchronized fun closeIfStalled(): Boolean {
        if(!stalled() || !closed.compareAndSet(false,true)) return false
        onSlowClose()
        finish()
        return true
    }
    @Synchronized override fun onWritePossible() = drain()
    override fun onError(error: Throwable) = close()
    @Synchronized private fun drain() {
        try {
            while(!isClosed) {
                if(!output.isReady) return
                val active=frame
                if(active != null) {
                    val count=minOf(8192,active.size-offset)
                    output.write(active,offset,count); offset+=count; dirty=true; progressAt=nanoTime()
                    if(offset == active.size) { frame=null; offset=0 }
                } else if(dirty) {
                    output.flush(); dirty=false; progressAt=nanoTime()
                } else if(latest != null) {
                    frame=latest; latest=null; offset=0
                } else return
            }
        } catch(_:Exception) { close() }
    }
    fun close() { if(closed.compareAndSet(false,true)) finish() }
}
