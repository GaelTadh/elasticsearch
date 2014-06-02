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
package org.elasticsearch.action.template.get;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.MasterNodeReadOperationRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

import static org.elasticsearch.action.ValidateActions.addValidationError;

/**
 * Request that allows to retrieve index templates
 */
public class GetSearchTemplatesRequest extends MasterNodeReadOperationRequest<GetSearchTemplatesRequest> {

    private String[] ids;

    public GetSearchTemplatesRequest() {
    }

    public GetSearchTemplatesRequest(String... ids) {
        this.ids = ids;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (ids == null) {
            validationException = addValidationError("ids is null or empty", validationException);
        } else {
            for (String name : ids) {
                if (name == null || !Strings.hasText(name)) {
                    validationException = addValidationError("name is missing", validationException);
                }
            }
        }
        return validationException;
    }

    /**
     * Sets the ids of the search templates.
     */
    public GetSearchTemplatesRequest names(String... ids) {
        this.ids = ids;
        return this;
    }

    /**
     * The ids of the search templates.
     */
    public String[] ids() {
        return this.ids;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        ids = in.readStringArray();
        readLocal(in, Version.V_1_0_0_RC2);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringArray(ids);
        writeLocal(out, Version.V_1_0_0_RC2);
    }
}
