/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.filter;

import com.alipay.sofa.rpc.common.struct.OrderedComparator;
import com.alipay.sofa.rpc.common.utils.CommonUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.AbstractInterfaceConfig;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.core.exception.SofaRpcException;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import com.alipay.sofa.rpc.ext.ExtensionClass;
import com.alipay.sofa.rpc.ext.ExtensionLoader;
import com.alipay.sofa.rpc.ext.ExtensionLoaderFactory;
import com.alipay.sofa.rpc.ext.ExtensionLoaderListener;
import com.alipay.sofa.rpc.invoke.Invoker;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chain of filter.
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public class FilterChain implements Invoker {

    /**
     * ??????
     */
    private static final Logger                              LOGGER                = LoggerFactory
                                                                                       .getLogger(FilterChain.class);

    /**
     * ???????????????????????? {"alias":ExtensionClass}
     */
    private final static Map<String, ExtensionClass<Filter>> PROVIDER_AUTO_ACTIVES = Collections
                                                                                       .synchronizedMap(new LinkedHashMap<String, ExtensionClass<Filter>>());

    /**
     * ???????????????????????? {"alias":ExtensionClass}
     */
    private final static Map<String, ExtensionClass<Filter>> CONSUMER_AUTO_ACTIVES = Collections
                                                                                       .synchronizedMap(new LinkedHashMap<String, ExtensionClass<Filter>>());

    /**
     * ???????????????
     */
    private final static ExtensionLoader<Filter>             EXTENSION_LOADER      = buildLoader();

    private static ExtensionLoader<Filter> buildLoader() {
        ExtensionLoader<Filter> extensionLoader = ExtensionLoaderFactory.getExtensionLoader(Filter.class);
        extensionLoader.addListener(new ExtensionLoaderListener<Filter>() {
            @Override
            public void onLoad(ExtensionClass<Filter> extensionClass) {
                Class<? extends Filter> implClass = extensionClass.getClazz();
                // ?????????????????????????????????
                AutoActive autoActive = implClass.getAnnotation(AutoActive.class);
                if (autoActive != null) {
                    String alias = extensionClass.getAlias();
                    if (autoActive.providerSide()) {
                        PROVIDER_AUTO_ACTIVES.put(alias, extensionClass);
                    }
                    if (autoActive.consumerSide()) {
                        CONSUMER_AUTO_ACTIVES.put(alias, extensionClass);
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Extension of interface " + Filter.class
                            + ", " + implClass + "(" + alias + ") will auto active");
                    }
                }
            }
        });
        return extensionLoader;
    }

    /**
     * ?????????
     */
    private FilterInvoker invokerChain;

    /**
     * ????????????????????????????????????
     */
    private List<Filter>  loadedFilters;

    /**
     * ???????????????
     *
     * @param filters     ?????????????????????
     * @param lastInvoker ???????????????
     * @param config      ????????????
     */
    protected FilterChain(List<Filter> filters, FilterInvoker lastInvoker, AbstractInterfaceConfig config) {
        // ???????????????????????????????????????filter
        // ??????????????????????????????
        invokerChain = lastInvoker;
        if (CommonUtils.isNotEmpty(filters)) {
            loadedFilters = new ArrayList<Filter>();
            for (int i = filters.size() - 1; i >= 0; i--) {
                try {
                    Filter filter = filters.get(i);
                    if (filter.needToLoad(invokerChain)) {
                        invokerChain = new FilterInvoker(filter, invokerChain, config);
                        // cache this for filter when async respond
                        loadedFilters.add(filter);
                    }
                } catch (SofaRpcRuntimeException e) {
                    LOGGER.error(LogCodes.getLog(LogCodes.ERROR_FILTER_CONSTRUCT), e);
                    throw e;
                } catch (Exception e) {
                    LOGGER.error(LogCodes.getLog(LogCodes.ERROR_FILTER_CONSTRUCT), e);
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_FILTER_CONSTRUCT), e);
                }
            }
        }
    }

    /**
     * ???????????????????????????
     *
     * @param providerConfig provider??????
     * @param lastFilter     ????????????filter
     * @return filter?????????
     */
    public static FilterChain buildProviderChain(ProviderConfig<?> providerConfig, FilterInvoker lastFilter) {
        return new FilterChain(selectActualFilters(providerConfig, PROVIDER_AUTO_ACTIVES), lastFilter, providerConfig);
    }

    /**
     * ???????????????????????????
     *
     * @param consumerConfig consumer??????
     * @param lastFilter     ????????????filter
     * @return filter?????????
     */
    public static FilterChain buildConsumerChain(ConsumerConfig<?> consumerConfig, FilterInvoker lastFilter) {
        return new FilterChain(selectActualFilters(consumerConfig, CONSUMER_AUTO_ACTIVES), lastFilter, consumerConfig);
    }

    /**
     * ??????????????????????????????
     *
     * @param config            provider????????????consumer??????
     * @param autoActiveFilters ????????????????????????????????????
     * @return ????????????????????????
     */
    private static List<Filter> selectActualFilters(AbstractInterfaceConfig config,
                                                    Map<String, ExtensionClass<Filter>> autoActiveFilters) {
        /*
         * ???????????????????????? A(a),B(b),C(c)  filter=[-a,d]  filterRef=[new E, new Exclude(b)]
         * ???????????????
         * 1.??????config.getFilterRef()?????????E???-b
         * 2.??????config.getFilter()?????????????????? d ??? -a,-b
         * 3.??????????????????????????????a,b???????????????????????????c,d
         * 4.???c d????????????
         * 5.??????C???D?????????
         * 6.????????????????????????C???D???E
         */
        // ??????????????????new????????????????????????filter???????????????
        List<Filter> customFilters = config.getFilterRef() == null ?
            new ArrayList<Filter>() : new CopyOnWriteArrayList<Filter>(config.getFilterRef());
        // ??????????????????????????????
        HashSet<String> excludes = parseExcludeFilter(customFilters);

        // ???????????????????????????????????????????????????filter???????????????
        List<ExtensionClass<Filter>> extensionFilters = new ArrayList<ExtensionClass<Filter>>();
        List<String> filterAliases = config.getFilter(); //
        if (CommonUtils.isNotEmpty(filterAliases)) {
            for (String filterAlias : filterAliases) {
                if (startsWithExcludePrefix(filterAlias)) { // ????????????????????????
                    excludes.add(filterAlias.substring(1));
                } else {
                    ExtensionClass<Filter> filter = EXTENSION_LOADER.getExtensionClass(filterAlias);
                    if (filter != null) {
                        extensionFilters.add(filter);
                    }
                }
            }
        }
        // ??????????????????????????????
        if (!excludes.contains(StringUtils.ALL) && !excludes.contains(StringUtils.DEFAULT)) { // ??????-*???-default?????????????????????
            for (Map.Entry<String, ExtensionClass<Filter>> entry : autoActiveFilters.entrySet()) {
                if (!excludes.contains(entry.getKey())) {
                    extensionFilters.add(entry.getValue());
                }
            }
        }
        // ???order??????????????????
        if (extensionFilters.size() > 1) {
            Collections.sort(extensionFilters, new OrderedComparator<ExtensionClass<Filter>>());
        }
        List<Filter> actualFilters = new ArrayList<Filter>();
        for (ExtensionClass<Filter> extensionFilter : extensionFilters) {
            actualFilters.add(extensionFilter.getExtInstance());
        }
        // ???????????????????????????
        actualFilters.addAll(customFilters);
        return actualFilters;
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param customFilters ?????????filter??????
     * @return ???????????????????????????key??????
     */
    private static HashSet<String> parseExcludeFilter(List<Filter> customFilters) {
        HashSet<String> excludeKeys = new HashSet<String>();
        if (CommonUtils.isNotEmpty(customFilters)) {
            for (Filter filter : customFilters) {
                if (filter instanceof ExcludeFilter) {
                    // ??????????????????????????????
                    ExcludeFilter excludeFilter = (ExcludeFilter) filter;
                    String excludeName = excludeFilter.getExcludeName();
                    if (StringUtils.isNotEmpty(excludeName)) {
                        String excludeFilterName = startsWithExcludePrefix(excludeName) ?
                            excludeName.substring(1)
                            : excludeName;
                        if (StringUtils.isNotEmpty(excludeFilterName)) {
                            excludeKeys.add(excludeFilterName);
                        }
                    }
                    customFilters.remove(filter);
                }
            }
        }
        if (!excludeKeys.isEmpty()) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Find exclude filters: {}", excludeKeys);
            }
        }
        return excludeKeys;
    }

    private static boolean startsWithExcludePrefix(String excludeName) {
        char c = excludeName.charAt(0);
        return c == '-' || c == '!';
    }

    @Override
    public SofaResponse invoke(SofaRequest sofaRequest) throws SofaRpcException {
        return invokerChain.invoke(sofaRequest);
    }

    /**
     * Do filtering when async respond from server
     *
     * @param config    Consumer config
     * @param request   SofaRequest
     * @param response  SofaResponse
     * @param throwable Throwable when invoke
     * @throws SofaRpcException occur error
     */
    public void onAsyncResponse(ConsumerConfig config, SofaRequest request, SofaResponse response, Throwable throwable)
        throws SofaRpcException {
        try {
            for (Filter loadedFilter : loadedFilters) {
                loadedFilter.onAsyncResponse(config, request, response, throwable);
            }
        } catch (SofaRpcException e) {
            LOGGER
                .errorWithApp(config.getAppName(), "Catch exception when do filtering after asynchronous respond.", e);
        }
    }

    /**
     * ???????????????
     *
     * @return chain
     */
    protected Invoker getChain() {
        return invokerChain;
    }

}
