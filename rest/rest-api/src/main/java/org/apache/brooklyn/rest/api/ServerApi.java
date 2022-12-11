/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.rest.api;

import java.io.InputStream;
import java.util.Map;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityMode;
import org.apache.brooklyn.api.mgmt.ha.ManagementNodeState;
import org.apache.brooklyn.rest.domain.HighAvailabilitySummary;
import org.apache.brooklyn.rest.domain.VersionSummary;

import com.google.common.annotations.Beta;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

@Path("/server")
@Api("Server")
@Produces(MediaType.APPLICATION_JSON)
@Beta
public interface ServerApi {

    public final String MIME_TYPE_ZIP = "application/zip";
    // TODO support TGZ, and check mime type
    public final String MIME_TYPE_TGZ = "application/gzip";
    
    @POST
    @Path("/properties/reload")
    @ApiOperation(value = "Reload brooklyn.properties")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Accepted"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public void reloadBrooklynProperties();

    @POST
    @Path("/shutdown")
    @ApiOperation(value = "Terminate this Brooklyn server instance")
    @Consumes({MediaType.APPLICATION_FORM_URLENCODED})
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Accepted"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public void shutdown(
            @ApiParam(name = "stopAppsFirst", value = "Whether to stop running applications before shutting down")
            @FormParam("stopAppsFirst") @DefaultValue("false") boolean stopAppsFirst,
            @ApiParam(name = "forceShutdownOnError", value ="Force shutdown if apps fail to stop or timeout")
            @FormParam("forceShutdownOnError") @DefaultValue("false") boolean forceShutdownOnError,
            @ApiParam(name = "shutdownTimeout", value = "A maximum delay to wait for apps to gracefully stop before giving up or forcibly exiting, 0 to wait infinitely")
            @FormParam("shutdownTimeout") @DefaultValue("20s") String shutdownTimeout,
            @ApiParam(name = "requestTimeout", value = "Maximum time to block the request for the shutdown to finish, 0 to wait infinitely")
            @FormParam("requestTimeout") @DefaultValue("20s") String requestTimeout,
            @ApiParam(name = "delayForHttpReturn", value = "The delay before exiting the process, to permit the REST response to be returned")
            @FormParam("delayForHttpReturn") @DefaultValue("5s") String delayForHttpReturn,
            @ApiParam(name = "delayMillis", value = "Deprecated, analogous to delayForHttpReturn")
            @FormParam("delayMillis") Long delayMillis);

    @GET
    @Path("/version")
    @ApiOperation(value = "Return version identifier information for this Brooklyn instance", 
            response = String.class,
            responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public VersionSummary getVersion();

    @GET
    @Path("/planeid")
    @ApiOperation(value = "Return the plane id (an identifier that is stable across restarts and HA failovers)")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public String getPlaneId();

    @GET
    @Path("/up")
    @ApiOperation(value = "Returns whether this server is up - fully started, and not stopping, though it may have errors")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public boolean isUp();
    
    @GET
    @Path("/shuttingDown")
    @ApiOperation(value = "Returns whether this server is shutting down")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public boolean isShuttingDown();
    
    @GET
    @Path("/healthy")
    @ApiOperation(value = "Returns whether this node is healthy - fully started, not stopping, and no errors")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public boolean isHealthy();

    @GET
    @Path("/up/extended")
    @ApiOperation(value = "Returns extended server-up information, a map including up (/up), shuttingDown (/shuttingDown), healthy (/healthy), and ha (/ha/states) (qv)"
            + " as well as selected settings such as sensitive field treatment"
            + "; also forces a session, so a useful general-purpose call for a UI client to do when starting")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public Map<String,Object> getUpExtended();

    @GET
    @Path("/config/{configKey}")
    @ApiOperation(value = "Get the value of the specified config key from brooklyn properties")
    @ApiResponses(value = {
            // TODO: This should probably return a 404 if the key is not present, and should return a predictable
            // value if the value is not set. Behaviour should be consistent with EntityConfigApi.get()
            @ApiResponse(code = 204, message = "Could not find config key"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 404, message = "Config key not found"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public String getConfig(
            @ApiParam(value = "Config key ID", required = true)
            @PathParam("configKey") String configKey,
            @ApiParam(value = "Whether to suppress secrets", required = false)
            @QueryParam("suppressSecrets") final Boolean suppressSecrets);

    @GET
    @Path("/ha/state")
    @ApiOperation(value = "Returns the HA state of this management node")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public ManagementNodeState getHighAvailabilityNodeState();
    
    @GET
    @Path("/ha/metrics")
    @ApiOperation(value = "Returns a collection of HA metrics")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public Map<String,Object> getHighAvailabilityMetrics();
    
    @POST
    @Path("/ha/state")
    @ApiOperation(value = "Changes the HA state of this management node")
    @Consumes({MediaType.APPLICATION_FORM_URLENCODED})
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Accepted"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public ManagementNodeState setHighAvailabilityNodeState(
            @ApiParam(name = "mode", value = "The state to change to")
            @FormParam("mode") HighAvailabilityMode mode);

    @GET
    @Path("/ha/states")
    @ApiOperation(value = "Returns the HA states and detail for all nodes in this management plane",
            response = org.apache.brooklyn.rest.domain.HighAvailabilitySummary.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public HighAvailabilitySummary getHighAvailabilityPlaneStates();

    @POST
    @Path("/ha/states/clear")
    @ApiOperation(value = "Clears HA node information for non-master nodes; active nodes will repopulate and other records will be erased")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Accepted"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public Response clearHighAvailabilityPlaneStates();

    @POST
    @Path("/ha/states/clear/node")
    @ApiOperation(value = "Clears HA node information for a particular non-master node; other nodes will repopulate and selected node will be erased")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Accepted"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public Response clearHighAvailabilityPlaneStates(
            @ApiParam(name = "nodeId", value = "ID of node to be removed")
            @FormParam("nodeId") String nodeId);
    
    @GET
    @Path("/ha/priority")
    @ApiOperation(value = "Returns the HA node priority for MASTER failover")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public long getHighAvailabilityPriority();
    
    @POST
    @Path("/ha/priority")
    @ApiOperation(value = "Sets the HA node priority for MASTER failover")
    @Consumes({MediaType.APPLICATION_FORM_URLENCODED})
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Accepted"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public long setHighAvailabilityPriority(
            @ApiParam(name = "priority", value = "The priority to be set")
            @FormParam("priority") long priority);
    
    @GET
    @Produces(MIME_TYPE_ZIP)
    @Path("/ha/persist/export")
    @ApiOperation(value = "Retrieves the persistence store data, as an archive")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public Response exportPersistenceData(
        @ApiParam(name = "origin", value = "Whether to take from LOCAL or REMOTE state; default to AUTO detect, "
                + "using LOCAL as master and REMOTE for other notes")
        @QueryParam("origin") @DefaultValue("AUTO") String origin);

    @POST
    @Path("/ha/persist/import")
    @Consumes
    @ApiOperation(value = "Imports a persistence export to a file-based store, moving catalog items, locations and managed applications (merged with the current persistence).")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Accepted"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public Response importPersistenceData(
        @ApiParam(name = "persistenceData", value = "Archived data", required = true) byte[] persistenceData);


    // TODO /ha/persist/backup set of endpoints, to list and retrieve specific backups

    @GET
    @Path("/user")
    @ApiOperation(value = "Return user information for this Brooklyn instance"
                + "; also forces a session, so a useful general-purpose call for a UI client to do when starting", 
            response = String.class,
            responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Bad Request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 500, message = "Internal Server Error")
    })
    public String getUser();

}
