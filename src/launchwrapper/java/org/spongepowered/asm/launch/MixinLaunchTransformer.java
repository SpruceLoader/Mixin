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
package org.spongepowered.asm.launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.spongepowered.asm.launch.platform.CommandLineOptions;
import xyz.spruceloader.launchwrapper.LaunchClassLoader;
import xyz.spruceloader.launchwrapper.api.ArgumentMap;
import xyz.spruceloader.launchwrapper.api.EnvSide;
import xyz.spruceloader.launchwrapper.api.LaunchTransformer;

/**
 * TweakClass for running mixins in production. Being a tweaker ensures that we
 * get injected into the AppClassLoader but does mean that we will need to
 * inject the FML coremod by hand if running under FML.
 */
public class MixinLaunchTransformer implements LaunchTransformer {
    /**
     * Hello world
     */
    public MixinLaunchTransformer() {
        MixinBootstrap.start();
    }

    public void takeArguments(ArgumentMap argMap, EnvSide env) {
        List<String> args = new ArrayList<>();
        Collections.addAll(args, argMap.toArray());
        MixinBootstrap.doInit(CommandLineOptions.ofArgs(args));
    }

    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        MixinBootstrap.inject();
    }

    public byte[] transform(String className, byte[] rawClass) {
        return rawClass;
    }
}
