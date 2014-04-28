/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.script;

import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ElasticsearchIllegalArgumentException;

import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.HppcMaps;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class ScriptService extends AbstractComponent {

    public static final String DEFAULT_SCRIPTING_LANGUAGE_SETTING = "script.default_lang";
    public static final String DISABLE_DYNAMIC_SCRIPTING_SETTING = "script.disable_dynamic";
    public static final String DISABLE_DYNAMIC_SCRIPTING_DEFAULT = "sandbox";

    private final String defaultLang;

    private final ImmutableMap<String, ScriptEngineService> scriptEngines;

    private final ConcurrentMap<String, CompiledScript> staticCache = ConcurrentCollections.newConcurrentMap();

    private final Cache<CacheKey, CompiledScript> cache;
    private final File scriptsDirectory;

    private final DynamicScriptDisabling dynamicScriptingDisabled;

    /**
     * Enum defining the different dynamic settings for scripting, either
     * ONLY_DISK_ALLOWED (scripts must be placed on disk), EVERYTHING_ALLOWED
     * (all dynamic scripting is enabled), or SANDBOXED_ONLY (only sandboxed
     * scripting languages are allowed)
     */
    enum DynamicScriptDisabling {
        EVERYTHING_ALLOWED,
        ONLY_DISK_ALLOWED,
        SANDBOXED_ONLY;

        public static final DynamicScriptDisabling parse(String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                // true for "disable_dynamic" means only on-disk scripts are enabled
                case "true":
                case "all":
                    return ONLY_DISK_ALLOWED;
                // false for "disable_dynamic" means all scripts are enabled
                case "false":
                case "none":
                    return EVERYTHING_ALLOWED;
                // only sandboxed scripting is enabled
                case "sandbox":
                case "sandboxed":
                    return SANDBOXED_ONLY;
                default:
                    throw new ElasticsearchIllegalArgumentException("Unrecognized script allowance setting: [" + s + "]");
            }
        }
    }
    private Client client = null;
    public static final String SCRIPT_INDEX = ".script";

    @Inject
    public ScriptService(Settings settings, Environment env, Set<ScriptEngineService> scriptEngines,
                         ResourceWatcherService resourceWatcherService) {
        super(settings);

        int cacheMaxSize = componentSettings.getAsInt("cache.max_size", 500);
        TimeValue cacheExpire = componentSettings.getAsTime("cache.expire", null);
        logger.debug("using script cache with max_size [{}], expire [{}]", cacheMaxSize, cacheExpire);

        this.defaultLang = settings.get(DEFAULT_SCRIPTING_LANGUAGE_SETTING, "groovy");
        this.dynamicScriptingDisabled = DynamicScriptDisabling.parse(settings.get(DISABLE_DYNAMIC_SCRIPTING_SETTING, DISABLE_DYNAMIC_SCRIPTING_DEFAULT));

        CacheBuilder cacheBuilder = CacheBuilder.newBuilder();
        if (cacheMaxSize >= 0) {
            cacheBuilder.maximumSize(cacheMaxSize);
        }
        if (cacheExpire != null) {
            cacheBuilder.expireAfterAccess(cacheExpire.nanos(), TimeUnit.NANOSECONDS);
        }
        this.cache = cacheBuilder.build();

        ImmutableMap.Builder<String, ScriptEngineService> builder = ImmutableMap.builder();
        for (ScriptEngineService scriptEngine : scriptEngines) {
            for (String type : scriptEngine.types()) {
                builder.put(type, scriptEngine);
            }
        }
        this.scriptEngines = builder.build();

        // put some default optimized scripts
        staticCache.put("doc.score", new CompiledScript("native", new DocScoreNativeScriptFactory()));

        // add file watcher for static scripts
        scriptsDirectory = new File(env.configFile(), "scripts");
        FileWatcher fileWatcher = new FileWatcher(scriptsDirectory);
        fileWatcher.addListener(new ScriptChangesListener());

        if (componentSettings.getAsBoolean("auto_reload_enabled", true)) {
            // automatic reload is enabled - register scripts
            resourceWatcherService.add(fileWatcher);
        } else {
            // automatic reload is disable just load scripts once
            fileWatcher.init();
        }
    }

    @Inject(optional=true)
    public void setClient(Client client) {
        this.client= client;
        /*
        //Ensure we have a ".scripts" index
        ListenableActionFuture<IndicesExistsResponse> lafResp = client.admin().indices().prepareExists(SCRIPT_INDEX).execute();
        try {
            //IndicesExistsResponse response = client.admin().indices().prepareExists(SCRIPT_INDEX).execute().get();

            IndicesExistsResponse response = null;
            response = lafResp.get();
            if( !response.isExists() ){

                CreateIndexRequestBuilder indexBuilder = client.admin().indices().prepareCreate(SCRIPT_INDEX);
                HashMap propertiesMap = new HashMap();
                HashMap disabledMap = new HashMap();
                disabledMap.put("enabled","false");
                for (String scriptType : scriptEngines.keySet()){
                    propertiesMap.put(scriptType,disabledMap);
                }
                HashMap<String,Object> nest = new HashMap<>();
                nest.put("properties", nest);
                indexBuilder.addMapping("__default__",nest);
                CreateIndexResponse cir = indexBuilder.get();
                if( !cir.isAcknowledged() ){
                    logger.error("Unable to create scripts index named " + SCRIPT_INDEX);
                }
            }
        } catch (InterruptedException ie){
            logger.error("Got InterruptedException creating scripts index",ie);
            //throw ie;
        } catch (ExecutionException ee) {
            logger.error("Got ExecutionException creating scripts index",ee);
            logger.error("Root failure ",lafResp.getRootFailure());
        }
        */
    }

    public void close() {
        for (ScriptEngineService engineService : scriptEngines.values()) {
            engineService.close();
        }
    }

    public CompiledScript compile(String script) {
        return compile(defaultLang, script);
    }

    public CompiledScript compile(String lang, String script) {
        CacheKey cacheKey = null;

        String scriptContent = null;
        CompiledScript compiled = null;

        if (script.startsWith("/")){ //This is how we determine if we need to search the index for the script
            if( client == null ){
                throw new ElasticsearchIllegalArgumentException("Got an indexed script with no Client registered.");
            }
            String[] parts = script.split("/");
            if (parts.length != 3) {
                throw new ElasticsearchIllegalArgumentException("Illegal index script format [" + script + "]" +
                        " should be /lang/id"  );
            } else {
                String scriptLang = parts[1];
                String id = parts[2];

                if (lang != null && !lang.equals(scriptLang)){
                    logger.trace("Overriding lang to " + scriptLang);
                    lang = scriptLang;
                    //cacheKey = new CacheKey(lang,script);
                }
                /*
                //No point in even querying the index if we have the script cached
                compiled = cache.getIfPresent(cacheKey);

                if (compiled != null) {
                    return compiled;
                }
                */
                scriptContent = getScriptFromIndex(script, SCRIPT_INDEX, scriptLang, id);
            }
        } else {
            compiled = staticCache.get(script); //Indexed scripts will never be in the static cache
            if (compiled != null) {
                return compiled;
            }
        }

        if (lang == null) {
            lang = defaultLang;
        }

        if (!dynamicScriptEnabled(lang)) {
            throw new ScriptException("dynamic scripting for [" + lang + "] disabled");
        }

        if (cacheKey == null) {
            cacheKey = new CacheKey(lang, script);
        }

        compiled = cache.getIfPresent(cacheKey);
        if (compiled != null) {
            return compiled;
        }

        // not the end of the world if we compile it twice...
        ScriptEngineService service = scriptEngines.get(lang);
        if (service == null) {
            throw new ElasticsearchIllegalArgumentException("script_lang not supported [" + lang + "]");
        }
        if (scriptContent != null) {
            compiled = new CompiledScript(lang, service.compile(scriptContent)); //We have loaded the script from the index
        } else {
            compiled = new CompiledScript(lang, service.compile(script));
            cache.put(cacheKey, compiled); //Only cache non indexed scripts here
        }


        return compiled;
    }

    private String getScriptFromIndex(String script, String index, String scriptLang, String id) {
        GetResponse responseFields = client.prepareGet(index, scriptLang, id).get();
        if (responseFields.isExists() ){
            return responseFields.getSourceAsString();
        } else {
            throw new ElasticsearchIllegalArgumentException("Unable to find script [" + script + "]");
        }
    }


    public ExecutableScript executable(String lang, String script, Map vars) {
        return executable(compile(lang, script), vars);
    }

    public ExecutableScript executable(CompiledScript compiledScript, Map vars) {
        return scriptEngines.get(compiledScript.lang()).executable(compiledScript.compiled(), vars);
    }

    public SearchScript search(CompiledScript compiledScript, SearchLookup lookup, @Nullable Map<String, Object> vars) {
        return scriptEngines.get(compiledScript.lang()).search(compiledScript.compiled(), lookup, vars);
    }

    public SearchScript search(SearchLookup lookup, String lang, String script, @Nullable Map<String, Object> vars) {
        return search(compile(lang, script), lookup, vars);
    }

    public SearchScript search(MapperService mapperService, IndexFieldDataService fieldDataService, String lang, String script, @Nullable Map<String, Object> vars) {
        return search(compile(lang, script), new SearchLookup(mapperService, fieldDataService, null), vars);
    }

    public Object execute(CompiledScript compiledScript, Map vars) {
        return scriptEngines.get(compiledScript.lang()).execute(compiledScript.compiled(), vars);
    }

    public void clear() {
        cache.invalidateAll();
    }

    private boolean dynamicScriptEnabled(String lang) {
        ScriptEngineService service = scriptEngines.get(lang);
        if (service == null) {
            throw new ElasticsearchIllegalArgumentException("script_lang not supported [" + lang + "]");
        }

        // Templating languages (mustache) and native scripts are always
        // allowed, "native" executions are registered through plugins
        if (this.dynamicScriptingDisabled == DynamicScriptDisabling.EVERYTHING_ALLOWED || "native".equals(lang) || "mustache".equals(lang)) {
            return true;
        } else if (this.dynamicScriptingDisabled == DynamicScriptDisabling.ONLY_DISK_ALLOWED) {
            return false;
        } else {
            return service.sandboxed();
        }
    }

    private class ScriptChangesListener extends FileChangesListener {

        private Tuple<String, String> scriptNameExt(File file) {
            String scriptPath = scriptsDirectory.toURI().relativize(file.toURI()).getPath();
            int extIndex = scriptPath.lastIndexOf('.');
            if (extIndex != -1) {
                String ext = scriptPath.substring(extIndex + 1);
                String scriptName = scriptPath.substring(0, extIndex).replace(File.separatorChar, '_');
                return new Tuple<>(scriptName, ext);
            } else {
                return null;
            }
        }

        @Override
        public void onFileInit(File file) {
            Tuple<String, String> scriptNameExt = scriptNameExt(file);
            if (scriptNameExt != null) {
                boolean found = false;
                for (ScriptEngineService engineService : scriptEngines.values()) {
                    for (String s : engineService.extensions()) {
                        if (s.equals(scriptNameExt.v2())) {
                            found = true;
                            try {
                                logger.info("compiling script file [{}]", file.getAbsolutePath());
                                String script = Streams.copyToString(new InputStreamReader(new FileInputStream(file), Charsets.UTF_8));
                                staticCache.put(scriptNameExt.v1(), new CompiledScript(engineService.types()[0], engineService.compile(script)));
                            } catch (Throwable e) {
                                logger.warn("failed to load/compile script [{}]", e, scriptNameExt.v1());
                            }
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }
                if (!found) {
                    logger.warn("no script engine found for [{}]", scriptNameExt.v2());
                }
            }
        }

        @Override
        public void onFileCreated(File file) {
            onFileInit(file);
        }

        @Override
        public void onFileDeleted(File file) {
            Tuple<String, String> scriptNameExt = scriptNameExt(file);
            logger.info("removing script file [{}]", file.getAbsolutePath());
            staticCache.remove(scriptNameExt.v1());
        }

        @Override
        public void onFileChanged(File file) {
            onFileInit(file);
        }

    }

    public static class CacheKey {
        public final String lang;
        public final String script;

        private CacheKey(){
            this.lang = null;
            this.script = null;
        }

        public CacheKey(String lang, String script) {
            this.lang = lang;
            this.script = script;
        }

        @Override
        public boolean equals(Object o) {
            CacheKey other = (CacheKey) o;
            return lang.equals(other.lang) && script.equals(other.script);
        }

        @Override
        public int hashCode() {
            return lang.hashCode() + 31 * script.hashCode();
        }
    }

    public static class DocScoreNativeScriptFactory implements NativeScriptFactory {
        @Override
        public ExecutableScript newScript(@Nullable Map<String, Object> params) {
            return new DocScoreSearchScript();
        }
    }

    public static class DocScoreSearchScript extends AbstractFloatSearchScript {
        @Override
        public float runAsFloat() {
            try {
                return doc().score();
            } catch (IOException e) {
                return 0;
            }
        }
    }
}
