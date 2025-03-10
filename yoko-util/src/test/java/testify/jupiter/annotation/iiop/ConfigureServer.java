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
package testify.jupiter.annotation.iiop;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.omg.CORBA.ORB;
import org.opentest4j.AssertionFailedError;
import testify.bus.Bus;
import testify.jupiter.annotation.ConfigurePartRunner;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.rmi.Remote;
import java.util.Optional;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;
import static testify.jupiter.annotation.iiop.ConfigureServer.Separation.INTER_ORB;
import static testify.jupiter.annotation.iiop.ConfigureServer.ServerName.DEFAULT_SERVER;

@ExtendWith(ServerExtension.class)
@Target({ANNOTATION_TYPE, TYPE})
@ConfigurePartRunner
@Retention(RUNTIME)
public @interface ConfigureServer {
    enum ServerName {DEFAULT_SERVER}
    enum Separation {COLLOCATED, INTER_ORB, INTER_PROCESS}

    ServerName serverName() default DEFAULT_SERVER;
    Separation separation() default INTER_ORB;
    String[] jvmArgs() default {};

    /** Define the config for the ORB the client for this server will use. */
    ConfigureOrb clientOrb() default @ConfigureOrb("client orb");

    /** Define the config for the ORB this server will use. */
    ConfigureOrb serverOrb() default @ConfigureOrb("server orb");

    /**
     * Annotate methods to be run in the server on ORB startup
     */
    @Target({ANNOTATION_TYPE, METHOD})
    @Retention(RUNTIME)
    @interface BeforeServer {
        ServerName value() default DEFAULT_SERVER;
    }

    /**
     * Annotate methods to be run in the server after the tests are completed
     */
    @Target({ANNOTATION_TYPE, METHOD})
    @Retention(RUNTIME)
    @interface AfterServer {
        ServerName value() default DEFAULT_SERVER;
    }

    /**
     * Annotate a static field in a test to inject a remote stub
     */
    @Target({ANNOTATION_TYPE, FIELD})
    @Retention(RUNTIME)
    @interface ClientStub {
        /** The implementation class of the remote object */
        Class<? extends Remote> value();
        /** Specify which server should host the remote object. */
        ServerName serverName() default DEFAULT_SERVER;
    }

    /**
     * Annotate a static field in a test to inject a corbaname URL for a remote object implementation
     */
    @Target({ANNOTATION_TYPE, FIELD})
    @Retention(RUNTIME)
    @interface CorbanameUrl {
        /** The implementation class of the remote object */
        Class<? extends Remote> value();
        /** A literal string to match the server name. Not a regular expression since the remote object can exist on only one server. */
        ServerName serverName() default DEFAULT_SERVER;
    }

    /**
     * Annotate a static field in a test to inject the name service URL
     */
    @Target({ANNOTATION_TYPE, FIELD})
    @Retention(RUNTIME)
    @interface NameServiceUrl {
        /** A literal string to match the server name. Not a regular expression since the remote object can exist on only one server. */
        ServerName serverName() default DEFAULT_SERVER;
    }


    /**
     * Annotate a static field in a test to inject a server control object
     */
    @Target({ANNOTATION_TYPE, FIELD})
    @Retention(RUNTIME)
    @interface Control {
        /** A literal string to match the server name. Not a regular expression since the controller controls exactly one server. */
        ServerName serverName() default DEFAULT_SERVER;
    }
}

class ServerExtension implements
        BeforeAllCallback, AfterAllCallback,
        BeforeEachCallback,
        BeforeTestExecutionCallback, AfterTestExecutionCallback,
        ParameterResolver {

    enum ParamType {
        BUS(Bus.class),
        CLIENT_ORB(ORB.class);

        final Class<?> type;
        ParamType(Class<?> type) { this.type = type; }

        static Optional<ParamType> forClass(Class<?> type) {
            for (ParamType t: values()) if (t.type == type) return Optional.of(t);
            return Optional.empty();
        }
    }

    @Override
    public void beforeAll(ExtensionContext ctx) {
        // check for conflicting annotations
        if (findAnnotation(ctx.getRequiredTestClass(), ConfigureOrb.class).isPresent()
                || findAnnotation(ctx.getElement(), ConfigureOrb.class).isPresent())
            fail(String.format("Use of @%s and @%s on the same test is unsupported.",
                    ConfigureOrb.class.getSimpleName(), ConfigureServer.class.getSimpleName()));
        ServerSteward.getInstance(ctx).beforeAll(ctx);
    }

    @Override
    public void beforeEach(ExtensionContext ctx) throws Exception { ServerSteward.getInstance(ctx).beforeEach(ctx); }


    @Override
    public boolean supportsParameter(ParameterContext pCtx, ExtensionContext ctx) {
        return ParamType.forClass(pCtx.getParameter().getType()).isPresent();
    }

    // Since the ServerSteward was retrieved from BeforeAll (i.e. in the test class context),
    // that is the one that will be found and reused from here (even if this is a test method context)
    @Override
    public Object resolveParameter(ParameterContext pCtx, ExtensionContext ctx) {
        return ParamType
                .forClass(pCtx.getParameter().getType())
                .map(t -> {
                    switch (t) {
                        case BUS: return ServerSteward.getInstance(ctx).getBus(ctx);
                        case CLIENT_ORB: return ServerSteward.getInstance(ctx).getClientOrb(ctx);
                        default: throw new AssertionFailedError("Unknown parameter type: " + t);
                    }
                })
                .orElseThrow(() -> new AssertionFailedError("Cannot resolve parameter for context " + pCtx));
    }

    @Override
    public void beforeTestExecution(ExtensionContext ctx) { ServerSteward.getInstance(ctx).beforeTestExecution(ctx); }

    @Override
    public void afterTestExecution(ExtensionContext ctx) { ServerSteward.getInstance(ctx).afterTestExecution(ctx); }

    @Override
    public void afterAll(ExtensionContext ctx) { ServerSteward.getInstance(ctx).afterAll(ctx); }
}