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
package org.elasticsearch.rest.action.script;

import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.indexedscripts.get.GetIndexedScriptRequest;
import org.elasticsearch.action.indexedscripts.get.GetIndexedScriptResponse;
import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptRequest;
import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.*;
import org.elasticsearch.index.query.TemplateQueryParser;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestBuilderListener;
import org.elasticsearch.rest.action.support.RestResponseListener;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestRequest.Method.PUT;
import static org.elasticsearch.rest.RestStatus.*;

/**
 *
 */
public class RestPutIndexedScriptAction extends BaseRestHandler {

    @Inject
    public RestPutIndexedScriptAction(Settings settings, Client client, RestController controller, ScriptService scriptService) {
        super(settings, client);

        controller.registerHandler(POST, "/_search/script/{lang}/{id}", this);
        controller.registerHandler(PUT, "/_search/script/{lang}/{id}", this);

        controller.registerHandler(PUT, "/_search/script/{lang}/{id}/_create", new CreateHandler(settings, client));
        controller.registerHandler(POST, "/_search/script/{lang}/{id}/_create", new CreateHandler(settings, client));
    }

    final class CreateHandler extends BaseRestHandler {
        protected CreateHandler(Settings settings, final Client client) {
            super(settings, client);
        }

        @Override
        public void handleRequest(RestRequest request, RestChannel channel, final Client client) {
            request.params().put("op_type", "create");
            RestPutIndexedScriptAction.this.handleRequest(request, channel, client);
        }
    }
    final class RenderHandler extends BaseRestHandler {
        ScriptService scriptService;
        protected RenderHandler(Settings settings, final Client client, final ScriptService scriptService) {
                        super(settings,client);
            this.scriptService = scriptService;
        }

        @Override
        public void handleRequest(final RestRequest request, RestChannel channel, final Client client){
            final String id = request.param("id");
            GetIndexedScriptRequest getReq = new GetIndexedScriptRequest();
            getReq.id(id);
            getReq.scriptLang("mustache");
            client.getIndexedScript(getReq, new RestResponseListener<GetIndexedScriptResponse>(channel) {
                @Override
                public RestResponse buildResponse(GetIndexedScriptResponse scriptResponse) {
                    try {
                        XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
                        if (!scriptResponse.isExists()) {
                            return new BytesRestResponse(RestStatus.NOT_FOUND, builder);
                        }
                        String script = scriptResponse.getScript();
                        CompiledScript compiledScript = scriptService.compile(script, "mustache", ScriptService.ScriptType.INLINE);
                        Map<String, Object> paramsMap = XContentHelper.convertToMap(request.content(), false).v2();
                        ExecutableScript executable = scriptService.executable("mustache", script, ScriptService.ScriptType.INLINE, paramsMap);
                        builder = XContentBuilder.builder(XContentFactory.xContent((BytesReference) executable.run()));
                        return new BytesRestResponse(RestStatus.OK, builder);
                    } catch (IOException ie) {
                        throw new ElasticsearchIllegalStateException("Unable to build response",ie);
                    }
                }
            });
        }
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, Client client) {

        PutIndexedScriptRequest putRequest = new PutIndexedScriptRequest(request.param("lang"), request.param("id"));
        putRequest.listenerThreaded(false);

        putRequest.source(request.content(), request.contentUnsafe());
        String sOpType = request.param("op_type");
        if (sOpType != null) {
            try {
                putRequest.opType(IndexRequest.OpType.fromString(sOpType));
            } catch (ElasticsearchIllegalArgumentException eia){
                try {
                    XContentBuilder builder = channel.newBuilder();
                    channel.sendResponse(new BytesRestResponse(BAD_REQUEST, builder.startObject().field("error", eia.getMessage()).endObject()));
                    return;
                } catch (IOException e1) {
                    logger.warn("Failed to send response", e1);
                    return;
                }
            }
        }

        client.putIndexedScript(putRequest, new RestBuilderListener<PutIndexedScriptResponse>(channel) {
            @Override
            public RestResponse buildResponse(PutIndexedScriptResponse response, XContentBuilder builder) throws Exception {
                builder.startObject()
                        .field(Fields._ID, response.getId())
                        .field(Fields._VERSION, response.getVersion())
                        .field(Fields.CREATED, response.isCreated());
                builder.endObject();
                RestStatus status = OK;
                if (response.isCreated()) {
                    status = CREATED;
                }
                return new BytesRestResponse(status, builder);
            }
        });
    }

    static final class Fields {
        static final XContentBuilderString _VERSION = new XContentBuilderString("_version");
        static final XContentBuilderString _ID = new XContentBuilderString("_id");
        static final XContentBuilderString CREATED = new XContentBuilderString("created");
    }

}
