/*
 * Copyright (C) 2014 Dell, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dell.doradus.service.olap.mono;

import com.dell.doradus.common.AggregateResult;
import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.HttpMethod;
import com.dell.doradus.common.TableDefinition;
import com.dell.doradus.common.UNode;
import com.dell.doradus.common.rest.RESTParameter;
import com.dell.doradus.olap.OlapAggregate;
import com.dell.doradus.service.olap.OLAPService;
import com.dell.doradus.service.rest.UNodeInOutCallback;
import com.dell.doradus.service.rest.annotation.Description;
import com.dell.doradus.service.rest.annotation.ParamDescription;

@Description(
    name = "Aggregate",
    summary = "Performs an aggregate query for a specific application and table using " +
              "the data in the 'mono' shard.",
    methods = {HttpMethod.GET, HttpMethod.PUT},
    uri = "/{application}/{table}/_aggregate",
    inputEntity = "aggregate-search",
    outputEntity = "results"
)
public class AggregateDocCmd extends UNodeInOutCallback {

    @ParamDescription
    public static RESTParameter describeInputEntity() {
        return new RESTParameter("aggregate-search")
                        .add("query", "text")
                        .add("grouping-fields", "text")
                        .add("metric", "text", true)
                        .add("pair", "text")
                        .add("flat", "boolean");
    }

    @Override
    public UNode invokeUNodeInOut(UNode inNode) {
        ApplicationDefinition appDef = m_request.getAppDef();
        TableDefinition tableDef = m_request.getTableDef(appDef);
        UNode rootNode = UNode.parse(m_request.getInputBody(), m_request.getInputContentType());
        UNode monoNode = OLAPMonoService.addMonoShard(rootNode);
        OlapAggregate request = new OlapAggregate(monoNode);
        AggregateResult aggResult = OLAPService.instance().aggregateQuery(tableDef, request);
        return aggResult.toDoc();
    }   // invokeUNodeOut

}   // class AggregateDocCmd
