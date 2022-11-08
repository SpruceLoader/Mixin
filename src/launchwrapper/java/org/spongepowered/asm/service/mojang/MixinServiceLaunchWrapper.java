/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.service.mojang;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.GlobalProperties;
import org.spongepowered.asm.launch.GlobalProperties.Keys;
import org.spongepowered.asm.launch.platform.MainAttributes;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.mixin.throwables.MixinException;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.ILegacyClassTransformer;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.transformers.MixinClassReader;
import org.spongepowered.asm.util.Constants;
import org.spongepowered.asm.util.Files;
import org.spongepowered.asm.util.perf.Profiler;
import org.spongepowered.asm.util.perf.Profiler.Section;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import xyz.spruceloader.launchwrapper.Launch;
import xyz.spruceloader.launchwrapper.api.LaunchTransformer;
import xyz.spruceloader.launchwrapper.api.LaunchTransformers;

/**
 * Mixin service for launchwrapper
 */
public class MixinServiceLaunchWrapper extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider, ITransformerProvider {
    // Blackboard keys
    public static final Keys BLACKBOARD_KEY_TWEAKCLASSES = Keys.of("TweakClasses");
    public static final Keys BLACKBOARD_KEY_TWEAKS = Keys.of("Tweaks");
    
    private static final String MIXIN_TWEAKER_CLASS = MixinServiceAbstract.LAUNCH_PACKAGE + "MixinTweaker";
    
    // Consts
    private static final String STATE_TWEAKER = MixinServiceAbstract.MIXIN_PACKAGE + "EnvironmentStateTweaker";
    private static final String TRANSFORMER_PROXY_CLASS = MixinServiceAbstract.MIXIN_PACKAGE + "transformer.Proxy";
    
    /**
     * Known re-entrant transformers, other re-entrant transformers will
     * detected automatically 
     */
    private static final Set<String> excludeTransformers = Sets.<String>newHashSet(
        "net.minecraftforge.fml.common.asm.transformers.EventSubscriptionTransformer",
        "cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer",
        "net.minecraftforge.fml.common.asm.transformers.TerminalTransformer",
        "cpw.mods.fml.common.asm.transformers.TerminalTransformer"
    );
    
    /**
     * Log4j2 logger
     */
    private static final Logger logger = LogManager.getLogger();

    /**
     * Utility for reflecting into Launch ClassLoader
     */
    private final LaunchClassLoaderUtil classLoaderUtil;
    
    /**
     * Local transformer chain, this consists of all transformers present at the
     * init phase with the exclusion of the mixin transformer itself and known
     * re-entrant transformers. Detected re-entrant transformers will be
     * subsequently removed.
     */
    private List<ILegacyClassTransformer> delegatedTransformers;
    
    public MixinServiceLaunchWrapper() {
        this.classLoaderUtil = new LaunchClassLoaderUtil(Launch.getInstance().getClassLoader());
    }
    
    @Override
    public String getName() {
        return "LaunchWrapper";
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#isValid()
     */
    @Override
    public boolean isValid() {
        try {
            // Detect launchwrapper
            Launch.getInstance().getClassLoader().hashCode();
        } catch (Throwable ex) {
            return false;
        }
        return true;
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#prepare()
     */
    @Override
    public void prepare() {
        // Only needed in dev, in production this would be handled by the tweaker
        Launch.getInstance().getClassLoader().addClassLoaderException(MixinServiceAbstract.LAUNCH_PACKAGE);
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getInitialPhase()
     */
    @Override
    public Phase getInitialPhase() {
        String command = System.getProperty("sun.java.command");
        if (command != null && command.contains("devlaunchinjector")) {
            System.setProperty("mixin.env.remapRefMap", "true");
        }

        if (MixinServiceLaunchWrapper.findInStackTrace("xyz.spruceloader.launchwrapper.Launch", "launch") > 132) {
            return Phase.DEFAULT;
        }
        return Phase.PREINIT;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService
     *      #getMaxCompatibilityLevel()
     */
    @Override
    public CompatibilityLevel getMaxCompatibilityLevel() {
        return CompatibilityLevel.JAVA_18;
    }
    
    @Override
    protected ILogger createLogger(String name) {
        return new LoggerAdapterLog4j2(name);
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#init()
     */
    @Override
    public void init() {
        if (MixinServiceLaunchWrapper.findInStackTrace("xyz.spruceloader.launchwrapper.Launch", "launch") < 4) {
            MixinServiceLaunchWrapper.logger.error("MixinBootstrap.doInit() called during a tweak constructor!");
        }

        List<String> tweakClasses = GlobalProperties.<List<String>>get(MixinServiceLaunchWrapper.BLACKBOARD_KEY_TWEAKCLASSES);
        if (tweakClasses != null) {
            tweakClasses.add(MixinServiceLaunchWrapper.STATE_TWEAKER);
        }
        
        super.init();
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getPlatformAgents()
     */
    @Override
    public Collection<String> getPlatformAgents() {
        return new ArrayList<>();
    }
    
    @Override
    public IContainerHandle getPrimaryContainer() {
        URI uri = null;
        try {
            uri = this.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            if (uri != null) {
                return new ContainerHandleURI(uri);
            }
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
        }
        return new ContainerHandleVirtual(this.getName());
    }
    
    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        Builder<IContainerHandle> list = ImmutableList.builder();
        this.getContainersFromClassPath(list);
        this.getContainersFromAgents(list);
        return list.build();
    }

    private void getContainersFromClassPath(Builder<IContainerHandle> list) {
        // We know this is deprecated, it works for LW though, so access directly
        URL[] sources = this.getClassPath();
        if (sources != null) {
            for (URL url : sources) {
                try {
                    URI uri = url.toURI();
                    MixinServiceLaunchWrapper.logger.debug("Scanning {} for mixin tweaker", uri);
                    if (!"file".equals(uri.getScheme()) || !Files.toFile(uri).exists()) {
                        continue;
                    }
                    MainAttributes attributes = MainAttributes.of(uri);
                    String tweaker = attributes.get(Constants.ManifestAttributes.TWEAKER);
                    if (MixinServiceLaunchWrapper.MIXIN_TWEAKER_CLASS.equals(tweaker)) {
                        list.add(new ContainerHandleURI(uri));
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                } 
            }
        }
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getClassProvider()
     */
    @Override
    public IClassProvider getClassProvider() {
        return this;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getBytecodeProvider()
     */
    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getTransformerProvider()
     */
    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getClassTracker()
     */
    @Override
    public IClassTracker getClassTracker() {
        return this.classLoaderUtil;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getAuditTrail()
     */
    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IClassProvider#findClass(
     *      java.lang.String)
     */
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Launch.getInstance().getClassLoader().loadClass(name);
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IClassProvider#findClass(
     *      java.lang.String, boolean)
     */
    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, Launch.getInstance().getClassLoader());
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IClassProvider#findAgentClass(
     *      java.lang.String, boolean)
     */
    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, Launch.class.getClassLoader());
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#beginPhase()
     */
    @Override
    public void beginPhase() {
        try {
            LaunchTransformers.addTransformer((LaunchTransformer) Class.forName(MixinServiceLaunchWrapper.TRANSFORMER_PROXY_CLASS).getDeclaredConstructor().newInstance());
            this.delegatedTransformers = null;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException |
                 ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#checkEnv(
     *      java.lang.Object)
     */
    @Override
    public void checkEnv(Object bootSource) {
        if (bootSource.getClass().getClassLoader() != Launch.class.getClassLoader()) {
            throw new MixinException("Attempted to init the mixin environment in the wrong classloader");
        }
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getResourceAsStream(
     *      java.lang.String)
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        return Launch.getInstance().getClassLoader().getResourceAsStream(name);
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IClassProvider#getClassPath()
     */
    @Override
    @Deprecated
    public URL[] getClassPath() {
        return Launch.getInstance().getClassPath().stream().map(path -> {
            try {
                return path.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }).toList().toArray(new URL[0]);
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getTransformers()
     */
    @Override
    public Collection<ITransformer> getTransformers() {
        List<LaunchTransformer> transformers = LaunchTransformers.getTransformers();
        List<ITransformer> wrapped = new ArrayList<>(transformers.size());
        for (LaunchTransformer transformer : transformers)
            wrapped.add(new LegacyTransformerHandle(transformer));
        return wrapped;
    }

    /**
     * Returns (and generates if necessary) the transformer delegation list for
     * this environment.
     * 
     * @return current transformer delegation list (read-only)
     */
    @Override
    public List<ITransformer> getDelegatedTransformers() {
        return Collections.unmodifiableList(this.getDelegatedLegacyTransformers());
    }
    
    private List<ILegacyClassTransformer> getDelegatedLegacyTransformers() {
        if (this.delegatedTransformers == null) {
            this.buildTransformerDelegationList();
        }
        
        return this.delegatedTransformers;
    }

    /**
     * Builds the transformer list to apply to loaded mixin bytecode. Since
     * generating this list requires inspecting each transformer by name (to
     * cope with the new wrapper functionality added by FML) we generate the
     * list just once per environment and cache the result.
     */
    private void buildTransformerDelegationList() {
        MixinServiceLaunchWrapper.logger.debug("Rebuilding transformer delegation list:");
        this.delegatedTransformers = new ArrayList<ILegacyClassTransformer>();
        for (ITransformer transformer : this.getTransformers()) {
            if (!(transformer instanceof ILegacyClassTransformer)) {
                continue;
            }
            
            ILegacyClassTransformer legacyTransformer = (ILegacyClassTransformer)transformer;
            String transformerName = legacyTransformer.getName();
            boolean include = true;
            for (String excludeClass : MixinServiceLaunchWrapper.excludeTransformers) {
                if (transformerName.contains(excludeClass)) {
                    include = false;
                    break;
                }
            }
            if (include && !legacyTransformer.isDelegationExcluded()) {
                MixinServiceLaunchWrapper.logger.debug("  Adding:    {}", transformerName);
                this.delegatedTransformers.add(legacyTransformer);
            } else {
                MixinServiceLaunchWrapper.logger.debug("  Excluding: {}", transformerName);
            }
        }

        MixinServiceLaunchWrapper.logger.debug("Transformer delegation list created with {} entries", this.delegatedTransformers.size());
    }

    /**
     * Adds a transformer to the transformer exclusions list
     * 
     * @param name Class transformer exclusion to add
     */
    @Override
    public void addTransformerExclusion(String name) {
        MixinServiceLaunchWrapper.excludeTransformers.add(name);
        
        // Force rebuild of the list
        this.delegatedTransformers = null;
    }

    /**
     * Retrieve class bytes using available classloaders, does not transform the
     * class
     * 
     * @param name class name
     * @return class bytes or null if not found
     * @throws IOException propagated
     * @deprecated Use {@link #getClassNode} instead
     */
    @Deprecated
    public byte[] getClassBytes(String name) throws ClassNotFoundException {
        byte[] classBytes = Launch.getInstance().getClassLoader().loadClassData(name);
        if (classBytes != null)
            return classBytes;

        URLClassLoader appClassLoader;
        if (Launch.class.getClassLoader() instanceof URLClassLoader) {
            appClassLoader = (URLClassLoader) Launch.class.getClassLoader();
        } else {
            appClassLoader = new URLClassLoader(new URL[]{}, Launch.class.getClassLoader());
        }

        InputStream classStream = null;
        try {
            final String resourcePath = name.replace('.', '/').concat(".class");
            classStream = appClassLoader.getResourceAsStream(resourcePath);
            return ByteStreams.toByteArray(classStream);
        } catch (Exception ex) {
            return null;
        } finally {
            Closeables.closeQuietly(classStream);
        }
    }
    
    /**
     * Loads class bytecode from the classpath
     * 
     * @param className Name of the class to load
     * @param runTransformers True to run the loaded bytecode through the
     *      delegate transformer chain
     * @return Transformed class bytecode for the specified class
     * @throws ClassNotFoundException if the specified class could not be loaded
     */
    @Deprecated
    public byte[] getClassBytes(String className, boolean runTransformers) throws ClassNotFoundException {
        Profiler profiler = Profiler.getProfiler("mixin");
        Section loadTime = profiler.begin(Profiler.ROOT, "class.load");
        byte[] classBytes = this.getClassBytes(className);
        loadTime.end();

        if (runTransformers) {
            Section transformTime = profiler.begin(Profiler.ROOT, "class.transform");
            classBytes = this.applyTransformers(className, classBytes, profiler);
            transformTime.end();
        }

        if (classBytes == null) {
            throw new ClassNotFoundException(String.format("The specified class '%s' was not found", className));
        }

        return classBytes;
    }

    /**
     * Since we obtain the class bytes with getClassBytes(), we need to apply
     * the transformers ourselves
     * 
     * @param name class name
     * @param basicClass input class bytes
     * @return class bytecode after processing by all registered transformers
     *      except the excluded transformers
     */
    private byte[] applyTransformers(String name, byte[] basicClass, Profiler profiler) {
        if (this.classLoaderUtil.isClassExcluded(name)) {
            return basicClass;
        }

        for (ILegacyClassTransformer transformer : this.getDelegatedLegacyTransformers()) {
            // Clear the re-entrance semaphore
            this.lock.clear();
            
            int pos = transformer.getName().lastIndexOf('.');
            String simpleName = transformer.getName().substring(pos + 1);
            Section transformTime = profiler.begin(Profiler.FINE, simpleName.toLowerCase(Locale.ROOT));
            transformTime.setInfo(transformer.getName());
            basicClass = transformer.transformClassBytes(name, basicClass);
            transformTime.end();
            
            if (this.lock.isSet()) {
                // Also add it to the exclusion list so we can exclude it if the environment triggers a rebuild
                this.addTransformerExclusion(transformer.getName());
                
                this.lock.clear();
                MixinServiceLaunchWrapper.logger.info("A re-entrant transformer '{}' was detected and will no longer process meta class data",
                        transformer.getName());
            }
        }

        return basicClass;
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IMixinService#getClassNode(
     *      java.lang.String)
     */
    @Override
    public ClassNode getClassNode(String className) throws ClassNotFoundException, IOException {
        return this.getClassNode(className, this.getClassBytes(className, true), ClassReader.EXPAND_FRAMES);
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.service.IClassBytecodeProvider#getClassNode(
     *      java.lang.String, boolean)
     */
    @Override
    public ClassNode getClassNode(String className, boolean runTransformers) throws ClassNotFoundException, IOException {
        return this.getClassNode(className, this.getClassBytes(className, true), ClassReader.EXPAND_FRAMES);
    }

    /**
     * Gets an ASM Tree for the supplied class bytecode
     * 
     * @param classBytes Class bytecode
     * @param flags ClassReader flags
     * @return ASM Tree view of the specified class 
     */
    private ClassNode getClassNode(String className, byte[] classBytes, int flags) {
        ClassNode classNode = new ClassNode();
        ClassReader classReader = new MixinClassReader(classBytes, className);
        classReader.accept(classNode, flags);
        return classNode;
    }

    private static int findInStackTrace(String className, String methodName) {
        Thread currentThread = Thread.currentThread();
        
        if (!"main".equals(currentThread.getName())) {
            return 0;
        }
        
        StackTraceElement[] stackTrace = currentThread.getStackTrace();
        for (StackTraceElement s : stackTrace) {
            if (className.equals(s.getClassName()) && methodName.equals(s.getMethodName())) {
                return s.getLineNumber();
            }
        }
        
        return 0;
    }
    
}
