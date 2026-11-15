package kr.ac.sejong.parking.stream

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import kr.ac.sejong.parking.zone.adapter.sse.NonBlockingSse
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class NonBlockingSseTest {
    private class Output:ServletOutputStream(){
        var ready=false;val bytes=ByteArrayOutputStream();var maximumChunk=Int.MAX_VALUE
        override fun isReady()=ready
        override fun setWriteListener(listener:WriteListener){}
        override fun write(b:Int){check(ready);bytes.write(b)}
        override fun write(b:ByteArray,off:Int,len:Int){check(ready);bytes.write(b,off,len);if(len>=maximumChunk) ready=false}
    }
    @Test fun `a blocked client never blocks a write and is closed after five seconds without progress`(){
        val out=Output();var now=0L;var finished=0;val stream=NonBlockingSse(out,{finished++},{now})
        stream.offer("snapshot".toByteArray());assertThat(out.bytes.size()).isZero()
        now=5_000_000_000;assertThat(stream.stalled()).isFalse()
        now++;assertThat(stream.stalled()).isTrue();stream.close();stream.close();assertThat(finished).isEqualTo(1)
    }
    @Test fun `slow client keeps a complete current frame plus only the latest next frame`(){
        val out=Output();val stream=NonBlockingSse(out,{})
        stream.offer("first\n\n".toByteArray());stream.offer("skipped\n\n".toByteArray());stream.offer("latest\n\n".toByteArray())
        out.ready=true;stream.onWritePossible();assertThat(out.bytes.toString()).isEqualTo("first\n\nlatest\n\n")
    }
    @Test fun `partial output resumes without duplicating or truncating a frame`(){
        val out=Output().apply {ready=true;maximumChunk=8192};val stream=NonBlockingSse(out,{})
        val frame="a".repeat(10000).toByteArray();stream.offer(frame);assertThat(out.bytes.size()).isEqualTo(8192)
        out.ready=true;stream.onWritePossible();assertThat(out.bytes.toByteArray()).isEqualTo(frame)
    }
    @Test fun `oversized frame closes the connection instead of growing memory`(){val out=Output();val stream=NonBlockingSse(out,{});stream.offer(ByteArray(1_048_577));assertThat(stream.isClosed).isTrue();assertThat(out.bytes.size()).isZero()}
}
