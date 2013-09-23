/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.ide.common.resources;

import static com.android.SdkConstants.PREFIX_RESOURCE_REF;
import static com.android.ide.common.resources.ResourceResolver.MAX_RESOURCE_INDIRECTION;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.res2.AbstractResourceRepository;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.ResourceType;

import java.util.List;

/**
 * Like {@link ResourceResolver} but for a single item, so it does not need the full resource maps
 * to be resolved up front. Typically used for cases where we may not have fully configured
 * resource maps and we need to look up a specific value, such as in Android Studio where
 * a color reference is found in an XML style file, and we want to resolve it in order to
 * display the final resolved color in the editor margin.
 */
public class ResourceItemResolver extends RenderResources {
    private final FolderConfiguration mConfiguration;
    private final LayoutLog mLogger;
    private final ResourceProvider mResourceProvider;
    private ResourceRepository mFrameworkResources;
    private ResourceResolver mResolver;
    private AbstractResourceRepository myAppResources;
    @Nullable private List<ResourceValue> mLookupChain;

    public ResourceItemResolver(
            @NonNull FolderConfiguration configuration,
            @NonNull ResourceProvider resourceProvider,
            @Nullable LayoutLog logger) {
        mConfiguration = configuration;
        mResourceProvider = resourceProvider;
        mLogger = logger;
        mResolver = resourceProvider.getResolver(false);
    }

    public ResourceItemResolver(
            @NonNull FolderConfiguration configuration,
            @NonNull ResourceRepository frameworkResources,
            @NonNull AbstractResourceRepository appResources,
            @Nullable LayoutLog logger) {
        mConfiguration = configuration;
        mResourceProvider = null;
        mLogger = logger;
        mFrameworkResources = frameworkResources;
        myAppResources = appResources;
    }

    @Override
    @Nullable
    public ResourceValue resolveResValue(@Nullable ResourceValue resValue) {
        if (mResolver != null) {
            return mResolver.resolveResValue(resValue);
        }
        return resolveResValue(resValue, 0);
    }

    @Nullable
    private ResourceValue resolveResValue(@Nullable ResourceValue resValue, int depth) {
        if (resValue == null) {
            return null;
        }

        // if the resource value is null, we simply return it.
        String value = resValue.getValue();
        if (value == null) {
            return resValue;
        }

        // else attempt to find another ResourceValue referenced by this one.
        ResourceValue resolvedResValue = findResValue(value, resValue.isFramework());

        // if the value did not reference anything, then we simply return the input value
        if (resolvedResValue == null) {
            return resValue;
        }

        // detect potential loop due to mishandled namespace in attributes
        if (resValue == resolvedResValue || depth >= MAX_RESOURCE_INDIRECTION) {
            if (mLogger != null) {
                mLogger.error(LayoutLog.TAG_BROKEN,
                        String.format(
                                "Potential stack overflow trying to resolve '%s': cyclic resource definitions? Render may not be accurate.",
                                value),
                        null);
            }
            return resValue;
        }

        // otherwise, we attempt to resolve this new value as well
        return resolveResValue(resolvedResValue, depth + 1);
    }

    @Override
    @Nullable
    public ResourceValue findResValue(@Nullable String reference, boolean inFramework) {
        if (mResolver != null) {
            return mResolver.findResValue(reference, inFramework);
        }

        if (reference == null) {
            return null;
        }

        ResourceUrl resource = ResourceUrl.parse(reference);
        if (resource != null && resource.hasValidName()) {
            if (resource.theme) {
                // Do theme lookup? We can't do that here; requires full global analysis, so just use
                // a real resource resolver
                ResourceResolver resolver = mResourceProvider.getResolver(true);
                if (resolver != null) {
                    return resolver.findResValue(reference, inFramework);
                } else {
                    return null;
                }
            } else if (reference.startsWith(PREFIX_RESOURCE_REF)) {
                return findResValue(resource.type, resource.name, inFramework || resource.framework);
            }
        }

        // Looks like the value didn't reference anything. Return null.
        return null;
    }

    private ResourceValue findResValue(ResourceType resType, String resName, boolean framework) {
        // map of ResourceValue for the given type
        // if allowed, search in the project resources first.
        if (!framework) {
            if (myAppResources == null) {
                assert mResourceProvider != null;
                myAppResources = mResourceProvider.getAppResources();
                if (myAppResources == null) {
                    return null;
                }
            }
            ResourceValue item = myAppResources.getConfiguredValue(resType, resName,
                    mConfiguration);
            if (item != null) {
                if (mLookupChain != null) {
                    mLookupChain.add(item);
                }
                return item;
            }
        } else {
            if (mFrameworkResources == null) {
                assert mResourceProvider != null;
                mFrameworkResources = mResourceProvider.getFrameworkResources();
                if (mFrameworkResources == null) {
                    return null;
                }
            }
            // now search in the framework resources.
            if (mFrameworkResources.hasResourceItem(resType, resName)) {
                ResourceItem item = mFrameworkResources.getResourceItem(resType, resName);
                ResourceValue value = item.getResourceValue(resType, mConfiguration, true);
                if (value != null && mLookupChain != null) {
                    mLookupChain.add(value);
                }
                return value;
            }
        }

        // didn't find the resource anywhere.
        if (mLogger != null) {
            mLogger.warning(LayoutLog.TAG_RESOURCES_RESOLVE,
                    "Couldn't resolve resource @" +
                            (framework ? "android:" : "") + resType + "/" + resName,
                    new ResourceValue(resType, resName, framework));
        }
        return null;
    }

    /**
     * Optional method to set a list the resolver should record all value resolutions
     * into. Useful if you want to find out the resolution chain for a resource,
     * e.g. {@code @color/buttonForeground => @color/foreground => @android:color/black }.
     * <p>
     * There is no getter. Clients setting this list should look it up themselves.
     * Note also that if this resolver has to delegate to a full resource resolver,
     * e.g. to follow theme attributes, those resolutions will not be recorded.
     *
     * @param lookupChain the list to set, or null
     */
    public void setLookupChainList(@Nullable List<ResourceValue> lookupChain) {
        mLookupChain = lookupChain;
    }

    /**
     * Interface implemented by clients of the {@link ResourceItemResolver} which allows
     * it to lazily look up the project resources, the framework resources and optionally
     * to provide a fully configured resource resolver, if any
     */
    public interface ResourceProvider {
        @Nullable ResourceResolver getResolver(boolean createIfNecessary);
        @Nullable ResourceRepository getFrameworkResources();
        @Nullable AbstractResourceRepository getAppResources();
    }
}