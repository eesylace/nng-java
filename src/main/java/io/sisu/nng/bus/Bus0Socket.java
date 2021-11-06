package io.sisu.nng.bus;

import io.sisu.nng.Nng;
import io.sisu.nng.NngException;
import io.sisu.nng.Socket;

public class Bus0Socket extends Socket {

    public Bus0Socket() throws NngException {
        super(Nng.lib()::nng_bus0_open);
    }
}
