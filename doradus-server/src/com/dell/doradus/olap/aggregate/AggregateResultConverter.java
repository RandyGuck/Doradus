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

package com.dell.doradus.olap.aggregate;

import java.util.ArrayList;
import java.util.List;

import com.dell.doradus.common.AggregateResult;
import com.dell.doradus.common.Utils;
import com.dell.doradus.olap.OlapAggregate;

public class AggregateResultConverter {
	public static AggregateResult create(AggregationResult result, OlapAggregate aggregate) {
		AggregateResult aggResult = new AggregateResult();
		aggResult.setMetricParam(aggregate.getMetrics());
		aggResult.setQueryParam(aggregate.getQuery());
		aggResult.setGroupingParam(aggregate.getFields());
		aggResult.setTotalObjects(result.documentsCount);
		
		if(result.summary == null) {
		    aggResult.setGlobalValue(Integer.toString(result.documentsCount));
		    return aggResult;
		}
		
		int metricsCount = result.summary.metricSet.values.length;
		
		List<String> metricValues = splitOuter(aggregate.getMetrics(), ',');
		Utils.require(metricValues.size() == metricsCount, "Unexpected metrics count");
		
		if(metricsCount == 1 && aggregate.getFields() == null) {
			aggResult.setGlobalValue(result.summary.metricSet.values[0].toString());
			return aggResult;
		}
		
		List<String> groupNames = parseAggregationGroups(aggregate.getFields());
		
		for(int i = 0; i < metricsCount; i++) {
			AggregateResult.AggGroupSet groupSet = aggResult.addGroupSet();
			groupSet.setGroupingParam(aggregate.getFields());
			groupSet.setMetricParam(metricValues.get(i).trim());
			groupSet.setGroupsetValue(result.summary.metricSet.values[i].toString());
			
			if(groupNames.size() == 0) continue;
			groupSet.setTotalGroups(result.groupsCount);
			
			for(AggregationResult.AggregationGroup group : result.groups) {
				AggregateResult.AggGroup grp = groupSet.addGroup();
				grp.setComposite(false);
				grp.setFieldName(groupNames.get(0));
				grp.setFieldValue(group.name == null ? "(null)" : group.name);
				grp.setGroupValue(group.metricSet.values[i].toString());
				if(group.innerResult != null) {
					addNested(group.innerResult, groupNames, 1, grp, i);
				}
			}
		}
		
		return aggResult;
	}
	
	// Split the given string at sepChr occurrences outside of parens/quotes.
    private static List<String> splitOuter(String str, char sepChr) {
        List<String> result = new ArrayList<String>();
        StringBuilder buffer = new StringBuilder();
        int nesting = 0;
        boolean bInQuote = false;
        for (int index = 0; index < str.length(); index++) {
            char ch = str.charAt(index);
            switch (ch) {
            case '(':
                buffer.append(ch);
                nesting++;
                break;
            case ')':
                buffer.append(ch);
                nesting--;
                break;
            case '\'':
            case '"':
                buffer.append(ch);
                if (bInQuote) {
                    nesting--;
                } else {
                    nesting++;
                }
                bInQuote = !bInQuote;
                break;
            case ',':
                if (nesting == 0) {
                    result.add(buffer.toString());
                    buffer.setLength(0);
                } else {
                    buffer.append(ch);
                }
                break;
            default:
                buffer.append(ch);
            }
        }
        if (buffer.length() > 0) {
            result.add(buffer.toString());
        }
        return result;
    }
    
	private static void addNested(AggregationResult res,
						   List<String> groupNames,
						   int aggGroupIndex,
						   AggregateResult.AggGroup parent,
						   int metricsIndex) {
		String groupName = groupNames.get(aggGroupIndex);
		
		for(AggregationResult.AggregationGroup group : res.groups) {
			AggregateResult.AggGroup grp = parent.addGroup();
			grp.setComposite(false);
			grp.setFieldName(groupName);
			grp.setFieldValue(group.name == null ? "(null)" : group.name);
			grp.setGroupValue(group.metricSet.values[metricsIndex].toString());
			if(group.innerResult != null) {
				addNested(group.innerResult, groupNames, aggGroupIndex + 1, grp, metricsIndex);
			}
		}
	}
	
	public static List<String> parseAggregationGroups(String groups) {
		List<String> result = new ArrayList<String>();
		if(groups == null) return result;
		while(groups.length() > 0) {
			int commaPos = groups.indexOf(',');
			int bracketPos = groups.indexOf('(');
			while(bracketPos >= 0 && commaPos >= 0 && bracketPos < commaPos) {
				int nested = 1;
				while(nested > 0) {
					int openBr = groups.indexOf('(', bracketPos + 1);
					int closeBr = groups.indexOf(')', bracketPos + 1);
					if(openBr >= 0 && openBr < commaPos) nested++;
					else if(closeBr >= 0) {
						nested--;
						commaPos = groups.indexOf(',', closeBr + 1);
						bracketPos = groups.indexOf('(', closeBr + 1);
					}
					else {
						nested = 0;
						commaPos = -1;
						bracketPos = -1;
					}
				}
			}
			String name = null;
			if(commaPos < 0) {
				name = groups;
				groups = "";
			}
			else {
				name = groups.substring(0, commaPos);
				groups = groups.substring(commaPos + 1);
			}
			int idx = name.indexOf(" AS ");
			if(idx >= 0) name = name.substring(idx + 4);
			else if(name.startsWith("TOP") || name.startsWith("BOTTOM")) {
				name = name.substring(name.indexOf(',') + 1, name.lastIndexOf(')'));
			}

			result.add(name.trim());
		}
		return result;
	}
}
































