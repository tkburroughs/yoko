/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.yoko;

import acme.Echo;
import acme.EchoImpl;
import org.junit.jupiter.api.Test;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextHelper;
import testify.bus.Bus;
import testify.jupiter.annotation.Tracing;
import testify.jupiter.annotation.iiop.ConfigureOrb;
import testify.jupiter.annotation.iiop.ConfigureServer;
import testify.jupiter.annotation.iiop.ConfigureServer.Control;
import testify.jupiter.annotation.iiop.ConfigureServer.NameServiceUrl;
import testify.jupiter.annotation.iiop.ConfigureServer.RemoteObject;
import testify.jupiter.annotation.iiop.ServerControl;

import java.rmi.RemoteException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static testify.jupiter.annotation.iiop.ConfigureOrb.NameService.READ_ONLY;

@ConfigureServer(orb = @ConfigureOrb(nameService = READ_ONLY))
@Tracing
public class ServerRestartTest {
    @RemoteObject(value = EchoImpl.class)
    public static Echo stub;

    @NameServiceUrl
    public static String nameServiceUrl;

    @Control
    public static ServerControl serverControl;

    @Test
    public void testServerControl(Bus bus, ORB clientOrb) throws Exception {
        // TODO: The IORs are not identical across restarts
        // TODO: and this test fails if the server has newProcess=true
        // TODO: fix both these things!
        assertEquals("hello", stub.echo("hello"));
        assertThrows(Exception.class, serverControl::start);
        serverControl.stop();
        assertThrows(RemoteException.class, () -> stub.echo(""));
        assertThrows(Exception.class, serverControl::stop);
        assertThrows(Exception.class, serverControl::restart);
        serverControl.start();
        serverControl.restart();
        serverControl.stop();
        Thread.sleep(2000);
        serverControl.start();
        assertEquals("hello", stub.echo("hello"));
    }

    @Test
    public void testNameServiceStarted(ORB clientOrb) throws Exception {
        assertNotNull(nameServiceUrl);
        NamingContextHelper.narrow(clientOrb.string_to_object(nameServiceUrl));
    }

}
