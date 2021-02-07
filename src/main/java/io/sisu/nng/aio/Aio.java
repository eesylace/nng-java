package io.sisu.nng.aio;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.sisu.nng.Message;
import io.sisu.nng.Nng;
import io.sisu.nng.NngException;
import io.sisu.nng.Socket;
import io.sisu.nng.internal.AioPointer;
import io.sisu.nng.internal.AioPointerByReference;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wrapper around NNG's aio (asynchronous io) structure, providing convenience methods and a
 * callback framework for making use from Java easier.
 *
 * In general, most methods are simply wrappers around the native NNG aio functions for manipulating
 * the aio. However, the introduction of the AioCallback approach provides a Java-friendly way to
 * provide callback event handlers. @see io.sisu.nng.aio.Context
 */
public class Aio implements AioProxy {
    //// DANGER
    public static AtomicInteger created = new AtomicInteger(0);
    public static AtomicInteger freed = new AtomicInteger(0);
    public static AtomicInteger invalidated = new AtomicInteger(0);
    public static AtomicInteger invalidatedAtTimeOfFreeing = new AtomicInteger(0);

    private final AioPointer aio;
    private final AioPointerByReference pointer;
    private AioCallback<?> cb;
    private AtomicBoolean valid = new AtomicBoolean(false);

    /**
     * Allocate a new NNG aio without a callback.
     * @throws NngException
     */
    public Aio() throws NngException {
        this(null);
    }

    /**
     * Allocate a new NNG aio with the given AioCallback set as the callback entrypoint.
     *
     * @param cb an instance of AioCallback for handling events on this aio
     * @throws NngException if nng_aio_alloc fails
     */
    public Aio(AioCallback cb) throws NngException {
        this.pointer = new AioPointerByReference();
        Native.setCallbackThreadInitializer(cb, new CallbackThreadInitializer(true, false, "AioCallback"));
        Native.setCallbackExceptionHandler(Nng.exceptionHandler);

        final int rv = Nng.lib().nng_aio_alloc(this.pointer, cb, Pointer.NULL);
        if (rv != 0) {
            throw new NngException(Nng.lib().nng_strerror(rv));
        }
        this.aio = this.pointer.getAioPointer();
        this.valid.set(true);
        this.cb = cb;
        if (cb != null) {
            this.cb.setAioProxy(this);
        }
        created.incrementAndGet();
    }

    public void setOutput(int index, ByteBuffer buffer) {
        if (index < 0 || index > 3) {
            throw new IndexOutOfBoundsException("index must be between 0 and 3");
        }

        if (buffer.isDirect()) {
            Nng.lib().nng_aio_set_output(aio, index, Native.getDirectBufferPointer(buffer));
        } else {
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(buffer.limit() - buffer.position());
            directBuffer.put(buffer);
            Nng.lib().nng_aio_set_output(aio, index, Native.getDirectBufferPointer(directBuffer));
        }
    }

    public String getOutputAsString(int index) {
        if (index < 0 || index > 3) {
            throw new IndexOutOfBoundsException("index must be between 0 and 3");
        }

        Pointer p = Nng.lib().nng_aio_get_output(aio, index);
        return p.getString(0);
    }

    public boolean begin() {
        return Nng.lib().nng_aio_begin(aio);
    }

    public void setTimeoutMillis(int timeoutMillis) {
        Nng.lib().nng_aio_set_timeout(aio, timeoutMillis);
    }

    public void finish(int err) {
        Nng.lib().nng_aio_finish(aio, err);
    }

    public void cancel() {
        Nng.lib().nng_aio_cancel(aio);
    }

    public int getResult() {
        return Nng.lib().nng_aio_result(aio);
    }

    public void waitForFinish() {
        Nng.lib().nng_aio_wait(aio);
    }

    public void assertSuccessful() throws NngException {
        int rv = Nng.lib().nng_aio_result(aio);
        if (rv != 0) {
            throw new NngException(Nng.lib().nng_strerror(rv));
        }
    }

    public AioPointer getAioPointer() {
        return this.aio;
    }

    @Override
    public void setMessage(Message msg) {
        Nng.lib().nng_aio_set_msg(aio, msg.getMessagePointer());
    }

    @Override
    public Message getMessage() {
        Pointer pointer = Nng.lib().nng_aio_get_msg(aio);

        // TODO: refactor into Optional<Message>? Throw directly?
        try {
            return new Message(pointer);
        } catch (NngException e) {
            return null;
        }
    }

    @Override
    public void sleep(int millis) {
        Nng.lib().nng_sleep_aio(millis, aio);
    }

    public void send(Socket socket) {
        Nng.lib().nng_send_aio(socket.getSocketStruct().byValue(), this.aio);
    }

    public void receive(Socket socket) {
        Nng.lib().nng_recv_aio(socket.getSocketStruct().byValue(), this.aio);
    }

    protected void free() {
        if (valid.compareAndSet(true, false)) {
            // System.out.println(String.format("JVM is freeing AIO %s", this));
            Nng.lib().nng_aio_free(this.aio);
            freed.incrementAndGet();
            invalidatedAtTimeOfFreeing.incrementAndGet();
        }
    }
}
