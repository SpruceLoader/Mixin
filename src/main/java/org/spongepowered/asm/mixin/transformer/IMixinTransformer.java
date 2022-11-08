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
package org.spongepowered.asm.mixin.transformer;

import java.util.List;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;
import org.spongepowered.asm.service.ILegacyClassTransformer;

/**
 * Transformation engine
 */
public interface IMixinTransformer {

    /**
     * Force-load all classes targetted by mixins but not yet applied
     * 
     * @param environment current environment
     */
    public abstract void audit(MixinEnvironment environment);

    /**
     * Update a mixin class with new bytecode.
     *
     * @param mixinClass Name of the mixin
     * @param classNode New bytecode
     * @return List of classes that need to be updated
     */
    public abstract List<String> reload(String mixinClass, ClassNode classNode);

    /**
     * Called when the transformation reason is computing_frames. The only
     * operation we care about here is adding interfaces to target classes but
     * at the moment we don't have sufficient scaffolding to determine that
     * without triggering re-entrance. Currently just a no-op in order to not
     * cause a re-entrance crash under ModLauncher 7.0+.
     * 
     * @param environment Current environment
     * @param name Class transformed name
     * @param classNode Class tree
     * @return true if the class was transformed
     */
    public abstract boolean computeFramesForClass(MixinEnvironment environment, String name, ClassNode classNode);

    /**
     * Callback from the hotswap agent and LaunchWrapper Proxy, transform class
     * bytecode. This method delegates to class generation or class
     * transformation based on whether the supplied byte array is <tt>null</tt>
     * and is therefore suitable for hosts which follow the LaunchWrapper
     * contract.
     * 
     * @param name Class name
     * @param transformedName Transformed class name
     * @param basicClass class bytecode
     * @return transformed class bytecode
     * 
     * @see ILegacyClassTransformer#transformClassBytes(String, String, byte[])
     */
    public abstract byte[] transformClassBytes(String name, byte[] basicClass);
    
    /**
     * Apply mixins and postprocessors to the supplied class
     * 
     * @param environment Current environment
     * @param name Class transformed name
     * @param classBytes Class bytecode
     * @return Transformed bytecode
     */
    public abstract byte[] transformClass(MixinEnvironment environment, String name, byte[] classBytes);

    /**
     * Apply mixins and postprocessors to the supplied class
     * 
     * @param environment Current environment
     * @param name Class transformed name
     * @param classNode Class tree
     * @return true if the class was transformed
     */
    public abstract boolean transformClass(MixinEnvironment environment, String name, ClassNode classNode);
    
    /**
     * Generate the specified mixin-synthetic class
     * 
     * @param environment Current environment
     * @param name Class name to generate
     * @return Generated bytecode or <tt>null</tt> if no class was generated
     */
    public abstract byte[] generateClass(MixinEnvironment environment, String name);
    
    /**
     * @param environment Current environment
     * @param name Class transformed name
     * @param classNode Empty classnode to populate
     * @return True if the class was generated successfully
     */
    public abstract boolean generateClass(MixinEnvironment environment, String name, ClassNode classNode);

    /**
     * Get the transformer extensions
     */
    public abstract IExtensionRegistry getExtensions();

}
