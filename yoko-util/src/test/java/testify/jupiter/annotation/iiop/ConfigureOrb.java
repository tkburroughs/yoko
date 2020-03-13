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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.omg.CORBA.ORB;
import org.omg.PortableInterceptor.ORBInitializer;
import testify.jupiter.annotation.impl.SimpleParameterResolver;
import testify.jupiter.annotation.impl.Steward;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.platform.commons.support.AnnotationSupport.isAnnotated;
import static org.junit.platform.commons.support.ModifierSupport.isPublic;
import static org.junit.platform.commons.support.ModifierSupport.isStatic;
import static testify.jupiter.annotation.iiop.OrbSteward.getOrb;
import static testify.streams.Collectors.requireNoMoreThanOne;

@ExtendWith(OrbExtension.class)
@Target({ANNOTATION_TYPE, TYPE})
@Retention(RUNTIME)
public @interface ConfigureOrb {
    String args() default "";
    String props() default "";

    @Target({ANNOTATION_TYPE, TYPE})
    @Retention(RUNTIME)
    @interface UseWithOrb {}
}

class OrbSteward extends Steward<ConfigureOrb> {
    private static final Class<?> CONNECTION_HELPER_CLASS;

    static {
        try {
            CONNECTION_HELPER_CLASS = Class.forName("org.apache.yoko.orb.OCI.IIOP.ExtendedConnectionHelper");
        } catch (ClassNotFoundException e) {
            throw new Error();
        }
    }

    @SuppressWarnings("unused")
    interface NullIiopConnectionHelper{}
    private final ORB orb;
    private OrbSteward(Class<?> testClass) {
        super(ConfigureOrb.class, testClass);
        this.orb = ORB.init(args(annotation, testClass, this::isOrbModifier), props(annotation, testClass, this::isOrbModifier)); }

    @Override
    // A CloseableResource stored in a context store is closed automatically when the context goes out of scope.
    // Note this happens *before* the correlated extension callback points (e.g. AfterEachCallback/AfterAllCallback)
    public void close() {
        orb.shutdown(true);
        orb.destroy();
    }

    private boolean isOrbModifier(Class<?> c) { return isAnnotated(c, ConfigureOrb.UseWithOrb.class); }

    /** Check that the supplied types are known types to be used as ORB extensions */
    private static void validateOrbModifierType(Class type) {
        assertTrue(isStatic(type), "Class " + type.getName() + " should be static");
        assertTrue(isPublic(type), "Class " + type.getName() + " should be public");
        // we know about ORB initializers
        if (ORBInitializer.class.isAssignableFrom(type)) return;
        // we also know about ConnectionHelpers
        if (CONNECTION_HELPER_CLASS.isAssignableFrom(type)) return;
        // we don't know about anything else!
        fail("Type " + type + " cannot be used with an ORB");
    }

    /** Extract the orb arguments from a {@link ConfigureOrb} annotation */
    static String[] args(ConfigureOrb cfg, Class<?> testClass, Predicate<Class<?>> nestedClassFilter) {
        return getNestedModifierTypes(testClass, nestedClassFilter)
                .filter(CONNECTION_HELPER_CLASS::isAssignableFrom)
                .collect(requireNoMoreThanOne("Only one connection helper can be configured but two were supplied: %s, %s"))
                .map(helper -> cfg.args() + " -IIOPconnectionHelper " + helper.getName())
                .orElse(cfg.args())
                .split(" ");
    }

    private static Stream<Class<?>> getNestedModifierTypes(Class<?> testClass, Predicate<Class<?>> nestedClassFilter) {
        return Stream.of(testClass.getDeclaredClasses())
                .filter(nestedClassFilter)
                .peek(OrbSteward::validateOrbModifierType);
    }

    /**
     * Extract the orb properties from a {@link ConfigureOrb} annotation.
     *
     * @param cfg the annotation to inspect for properties
     * @param testClass the test class on which the annotation is specified
     * @param nestedClassFilter a boolean predicate to test whether to process the nested annotation types
     */
    static Properties props(ConfigureOrb cfg, Class<?> testClass, Predicate<Class<?>> nestedClassFilter) {
        Properties props = new Properties();
        props.put("org.omg.CORBA.ORBClass", "org.apache.yoko.orb.CORBA.ORB");
        props.put("org.omg.CORBA.ORBSingletonClass", "org.apache.yoko.orb.CORBA.ORBSingleton");
        for (String prop : cfg.props().split(" ")) {
            if (prop.isEmpty()) continue;
            String[] arr = prop.split("=", 2);
            props.put(arr[0], arr.length < 2 ? "" : arr[1]);
        }
        // add initializer properties for each specified initializer class
        getNestedModifierTypes(testClass, nestedClassFilter)
                .filter(ORBInitializer.class::isAssignableFrom)
                .forEachOrdered(initializer -> addORBInitializerProp(props,  (Class<? extends ORBInitializer>) initializer));
        return props;
    }

    private static void addORBInitializerProp(Properties props, Class<? extends ORBInitializer> initializer) {
        String name = ORBInitializer.class.getName() + "Class." + initializer.getName();
        // blow up if this has been specified twice since this suggests a configuration error
        Assertions.assertFalse(props.contains(name), initializer.getName() + " should only be configured in one place");
        props.put(name, "true");
    }

    /** Get a client ORB */
    static ORB getOrb(ExtensionContext ctx) { return Steward.getInstanceForContext(ctx, OrbSteward.class, OrbSteward::new).orb; }
}

/** Must be registered using the {@link ExtendWith} annotation */
class OrbExtension implements Extension, BeforeAllCallback, SimpleParameterResolver<ORB> {
    @Override
    // to ensure the ORB is created only once per test class, create the steward here
    public void beforeAll(ExtensionContext ctx) { getOrb(ctx); }

    @Override
    // assume that any child class of org.omg.CORBA.ORB will be satisfied
    public boolean supportsParameter(ParameterContext ctx) { return ORB.class.isAssignableFrom(ctx.getParameter().getType()); }

    @Override
    // get the configured ORB for the context,
    // but if the context has a test method, use its parent instead
    // i.e. get an ORB for the test class, not for each test method
    public ORB resolveParameter(ExtensionContext ctx) { return getOrb(ctx); }
}