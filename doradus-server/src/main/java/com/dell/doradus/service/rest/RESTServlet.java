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

package com.dell.doradus.service.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.HttpCode;
import com.dell.doradus.common.HttpDefs;
import com.dell.doradus.common.HttpMethod;
import com.dell.doradus.common.Pair;
import com.dell.doradus.common.RESTResponse;
import com.dell.doradus.common.Utils;
import com.dell.doradus.core.DoradusServer;
import com.dell.doradus.service.db.DBNotAvailableException;
import com.dell.doradus.service.db.DuplicateException;
import com.dell.doradus.service.db.UnauthorizedException;
import com.dell.doradus.service.schema.SchemaService;

/**
 * An HttpServlet implementation used by the {@link RESTService} to process REST requests.
 * Each request is matched to a registered REST command and, if found, is executed by
 * invoking the command's callback. If the the request is unknown, a 404 response is
 * return. Exceptions thrown by the callback are mapped to 4xx or 5xx responses as 
 * appropriate.
 */
public class RESTServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private final Logger m_logger = LoggerFactory.getLogger(getClass().getSimpleName());
    
    //----- Inherited methods from HttpServlet
    
    /**
     * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            long startNano = System.nanoTime();
            RESTResponse restResponse = validateAndExecuteRequest(request);
            sendResponse(response, restResponse);
            m_logger.debug("Elapsed time: {} millis; request={}",
                           (float)(System.nanoTime() - startNano)/1000000, getFullURI(request));
        } catch (IllegalArgumentException e) {
            // 400 Bad Request
            RESTResponse restResponse = new RESTResponse(HttpCode.BAD_REQUEST, getNestedMessage(e));
            m_logger.info("Returning client error: {}; request: {}",
                          restResponse.toString(), getFullURI(request));
            sendResponse(response, restResponse);
        } catch (NotFoundException e) {
            // 404 Not Found
            RESTResponse restResponse = new RESTResponse(HttpCode.NOT_FOUND, e.getMessage());
            m_logger.info("Returning client error: {}; request: {}",
                          restResponse.toString(), getFullURI(request));
            sendResponse(response, restResponse);
        } catch (DBNotAvailableException e) {
            // 503 Service Unavailable
            RESTResponse restResponse = new RESTResponse(HttpCode.SERVICE_UNAVAILABLE, e.getMessage());
            m_logger.info("Returning service error: {}; request: {}",
                          restResponse.toString(), getFullURI(request));
            sendResponse(response, restResponse);
        } catch (UnauthorizedException e) {
            // 401 Unauthorized
            RESTResponse restResponse = new RESTResponse(HttpCode.UNAUTHORIZED, e.getMessage());
            m_logger.info("Returning client error: {}; request: {}",
                          restResponse.toString(), getFullURI(request));
            sendResponse(response, restResponse);
        } catch (DuplicateException e) {
            // 409 Conflict
            RESTResponse restResponse = new RESTResponse(HttpCode.CONFLICT, e.getMessage());
            m_logger.info("Returning client error: {}; request: {}",
                          restResponse.toString(), getFullURI(request));
            sendResponse(response, restResponse);
        } catch (OutOfMemoryError e) {
            // Report a fatal exception then shutdown. This is considered a better
            // response to OOM then than continuing in an unstable state.
            m_logger.error("Fatal: shutting down. Consider increasing memory or reducing " +
                           "the size of large input batches or queries.", e);
            DoradusServer.shutDown();
            System.exit(1);
        } catch (Throwable e) {
            // 500 Internal Error: include a stack trace and report in log.
            m_logger.error("Unexpected exception handling request: " + getFullURI(request), e);
            String stackTrace = Utils.getStackTrace(e);
            RESTResponse restResponse = new RESTResponse(HttpCode.INTERNAL_ERROR, stackTrace);
            sendResponse(response, restResponse);
        }
    }    // goGet
    
    /**
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }    // doPost
    
    /**
     * @see HttpServlet#doPut(HttpServletRequest request, HttpServletResponse response)
     */
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }    // doPut
    
    /**
     * @see HttpServlet#doDelete(HttpServletRequest request, HttpServletResponse response)
     */
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }    // doDelete
    
    //----- Private methods

    private String getNestedMessage(Throwable e) {
        String localMsg = e.getMessage();
        if (Utils.isEmpty(localMsg)) {
            localMsg = e.getClass().getSimpleName();
        }
        Throwable embeddedException = e.getCause();
        if (embeddedException == null) {
            return localMsg;
        }
        return localMsg + ": " + getNestedMessage(embeddedException);
    }
    
    // Execute the given request and return a RESTResponse or throw an appropriate error.
    private RESTResponse validateAndExecuteRequest(HttpServletRequest request) {
        Map<String, String> variableMap = new HashMap<String, String>();
        String query = extractQueryParam(request, variableMap);

        // Command matching expects an encoded URI but without the servlet context, if any.
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (!Utils.isEmpty(context)) {
            uri = uri.substring(context.length());
        }
        ApplicationDefinition appDef = getApplication(uri);
        
        HttpMethod method = HttpMethod.methodFromString(request.getMethod());
        if (method == null) {
            throw new NotFoundException("Request does not match a known URI: " + request.getRequestURL());
        }
        
        RegisteredCommand cmd = RESTService.instance().findCommand(appDef, method, uri, query, variableMap);
        if (cmd == null) {
            throw new NotFoundException("Request does not match a known URI: " + request.getRequestURL());
        }
        
        RESTCallback callback = cmd.getNewCallback();
        callback.setRequest(new RESTRequest(appDef, request, variableMap));
        return callback.invoke();
    }
    
    // Get the definition of the referenced application or null if there is none.
    private ApplicationDefinition getApplication(String uri) {
        if (uri.length() < 2 || uri.startsWith("/_")) {
            return null;    // Non-application request
        }
        String[] pathNodes = uri.substring(1).split("/");
        String appName = Utils.urlDecode(pathNodes[0]);
        ApplicationDefinition appDef = SchemaService.instance().getApplication(appName);
        if (appDef == null) {
            throw new NotFoundException("Unknown application: " + appName);
        }
        return appDef;
    }
    
    // Send the given response, which includes a response code and optionally a body
    // and/or additional response headers. If the body is non-empty, we automatically add
    // the Content-Length and a Content-Type of Text/plain.
    private void sendResponse(HttpServletResponse servletResponse, RESTResponse restResponse) throws IOException {
        servletResponse.setStatus(restResponse.getCode().getCode());
        
        Map<String, String> responseHeaders = restResponse.getHeaders();
        if (responseHeaders != null) {
            for (Map.Entry<String, String> mapEntry : responseHeaders.entrySet()) {
                if (mapEntry.getKey().equalsIgnoreCase(HttpDefs.CONTENT_TYPE)) {
                    servletResponse.setContentType(mapEntry.getValue());
                } else {
                    servletResponse.setHeader(mapEntry.getKey(), mapEntry.getValue());
                }
            }
        }
        
        byte[] bodyBytes = restResponse.getBodyBytes();
        int bodyLen = bodyBytes == null ? 0 : bodyBytes.length;
        servletResponse.setContentLength(bodyLen);
        
        if (bodyLen > 0 && servletResponse.getContentType() == null) {
            servletResponse.setContentType("text/plain");
        }
        
        if (bodyLen > 0) {
            servletResponse.getOutputStream().write(restResponse.getBodyBytes());
        }
    }   // sendResponse
    
    // Extract and return the query component of the given request, but move "api=x" and
    // "format=y", if present, to rest parameters.
    private String extractQueryParam(HttpServletRequest request, Map<String, String> restParams) {
        String query = request.getQueryString();
        if (Utils.isEmpty(query)) {
            return "";
        }
        StringBuilder buffer = new StringBuilder(query);
        
        // Split query component into decoded, &-separate components.
        String[] parts = Utils.splitURIQuery(buffer.toString());
        List<Pair<String, String>> unusedList = new ArrayList<Pair<String,String>>();
        boolean bRewrite = false;
        for (String part : parts) {
            Pair<String, String> param = extractParam(part);
            switch (param.firstItemInPair.toLowerCase()) {
            case "api":
                bRewrite = true;
                restParams.put("api", param.secondItemInPair);
                break;
            case "format":
                bRewrite = true;
                if (param.secondItemInPair.equalsIgnoreCase("xml")) {
                    restParams.put("format", "text/xml");
                } else if (param.secondItemInPair.equalsIgnoreCase("json")) {
                    restParams.put("format", "application/json");
                }
                break;
            default:
                unusedList.add(param);
            }
        }
        
        // If we extracted any fixed params, rewrite the query parameter.
        if (bRewrite) {
            buffer.setLength(0);
            for (Pair<String, String> pair : unusedList) {
                if (buffer.length() > 0) {
                    buffer.append("&");
                }
                buffer.append(Utils.urlEncode(pair.firstItemInPair));
                if (pair.secondItemInPair != null) {
                    buffer.append("=");
                    buffer.append(Utils.urlEncode(pair.secondItemInPair));
                }
            }
        }
        return buffer.toString();
    }   // extractQueryParam

    // Split the given k=v (or just k) param into a Pair object.
    private Pair<String, String> extractParam(String part) {
        int eqInx = part.indexOf('=');
        String paramName;
        String paramValue;
        if (eqInx < 0) {
            paramName = part;
            paramValue = null;
        } else {
            paramName = part.substring(0, eqInx);
            paramValue = part.substring(eqInx + 1);
        }
        return Pair.create(paramName, paramValue);
    }   // extractParam
    
    // Reconstruct the entire URI from the given request.
    private String getFullURI(HttpServletRequest request) {
        StringBuilder buffer = new StringBuilder(request.getMethod());
        buffer.append(" ");
        buffer.append(request.getRequestURI());
        String queryParam = request.getQueryString();
        if (!Utils.isEmpty(queryParam)) {
            buffer.append("?");
            buffer.append(queryParam);
        }
        return buffer.toString();
    }   // getFullURI
    
}   // class RESTServlet
