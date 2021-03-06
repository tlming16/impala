// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.authorization.sentry;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.impala.authorization.AuthorizationChecker;
import org.apache.impala.authorization.AuthorizationDelta;
import org.apache.impala.authorization.AuthorizationException;
import org.apache.impala.authorization.AuthorizationManager;
import org.apache.impala.authorization.User;
import org.apache.impala.catalog.Role;
import org.apache.impala.common.ImpalaException;
import org.apache.impala.common.InternalException;
import org.apache.impala.common.JniUtil;
import org.apache.impala.service.FeCatalogManager;
import org.apache.impala.service.FeSupport;
import org.apache.impala.thrift.TCatalogServiceRequestHeader;
import org.apache.impala.thrift.TCreateDropRoleParams;
import org.apache.impala.thrift.TDdlExecResponse;
import org.apache.impala.thrift.TErrorCode;
import org.apache.impala.thrift.TGrantRevokePrivParams;
import org.apache.impala.thrift.TGrantRevokeRoleParams;
import org.apache.impala.thrift.TResultSet;
import org.apache.impala.thrift.TSentryAdminCheckRequest;
import org.apache.impala.thrift.TSentryAdminCheckResponse;
import org.apache.impala.thrift.TShowGrantPrincipalParams;
import org.apache.impala.thrift.TShowRolesParams;
import org.apache.impala.thrift.TShowRolesResult;
import org.apache.impala.util.ClassUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An implementation of {@link AuthorizationManager} for Impalad that uses Sentry.
 *
 * The methods here use the authorization data stored in Impalad catalog via
 * {@link org.apache.impala.authorization.AuthorizationPolicy}.
 *
 * Other non-Impalad operations, such as GRANT, REVOKE will throw
 * {@link UnsupportedOperationException}.
 */
public class SentryImpaladAuthorizationManager implements AuthorizationManager {
  private static final Logger LOG =
      LoggerFactory.getLogger(SentryImpaladAuthorizationManager.class);

  private final FeCatalogManager catalog_;
  private final Supplier<? extends SentryAuthorizationChecker> authzChecker_;

  public SentryImpaladAuthorizationManager(FeCatalogManager catalog,
      Supplier<? extends AuthorizationChecker> authzChecker) {
    Preconditions.checkNotNull(catalog);
    Preconditions.checkNotNull(authzChecker);
    Preconditions.checkArgument(authzChecker.get() instanceof SentryAuthorizationChecker);
    catalog_ = catalog;
    authzChecker_ = (Supplier<SentryAuthorizationChecker>) authzChecker;
  }

  @Override
  public void createRole(User requestingUser, TCreateDropRoleParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void dropRole(User requestingUser, TCreateDropRoleParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public TShowRolesResult getRoles(TShowRolesParams params) throws ImpalaException {
    if (params.isIs_admin_op()) {
      validateSentryAdmin(params.getRequesting_user());
    }

    TShowRolesResult result = new TShowRolesResult();
    List<Role> roles = Lists.newArrayList();
    if (params.isIs_show_current_roles() || params.isSetGrant_group()) {
      User user = new User(params.getRequesting_user());
      Set<String> groupNames;
      if (params.isIs_show_current_roles()) {
        groupNames = authzChecker_.get().getUserGroups(user);
      } else {
        Preconditions.checkState(params.isSetGrant_group());
        groupNames = Sets.newHashSet(params.getGrant_group());
      }
      for (String groupName: groupNames) {
        roles.addAll(catalog_.getOrCreateCatalog().getAuthPolicy()
            .getGrantedRoles(groupName));
      }
    } else {
      Preconditions.checkState(!params.isIs_show_current_roles());
      roles = catalog_.getOrCreateCatalog().getAuthPolicy().getAllRoles();
    }

    result.setRole_names(Lists.newArrayListWithExpectedSize(roles.size()));
    for (Role role: roles) {
      result.getRole_names().add(role.getName());
    }

    Collections.sort(result.getRole_names());
    return result;
  }

  @Override
  public void grantRoleToGroup(User requestingUser, TGrantRevokeRoleParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void revokeRoleFromGroup(User requestingUser, TGrantRevokeRoleParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void grantPrivilegeToRole(User requestingUser,
      TGrantRevokePrivParams params, TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void revokePrivilegeFromRole(User requestingUser, TGrantRevokePrivParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void grantPrivilegeToUser(User requestingUser, TGrantRevokePrivParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void revokePrivilegeFromUser(User requestingUser, TGrantRevokePrivParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void grantPrivilegeToGroup(User requestingUser, TGrantRevokePrivParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void revokePrivilegeFromGroup(User requestingUser, TGrantRevokePrivParams params,
      TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public TResultSet getPrivileges(TShowGrantPrincipalParams params)
      throws ImpalaException {
    if (params.isIs_admin_op()) {
      validateSentryAdmin(params.getRequesting_user());
    }

    switch (params.getPrincipal_type()) {
      case USER:
        Set<String> groupNames = authzChecker_.get().getUserGroups(
            new User(params.getName()));
        return catalog_.getOrCreateCatalog().getAuthPolicy().getUserPrivileges(
            params.getName(), groupNames, params.getPrivilege());
      case ROLE:
        return catalog_.getOrCreateCatalog().getAuthPolicy().getRolePrivileges(
            params.getName(), params.getPrivilege());
      default:
        throw new InternalException(String.format("Unexpected TPrincipalType: %s",
            params.getPrincipal_type()));
    }
  }

  @Override
  public void updateDatabaseOwnerPrivilege(String serverName, String databaseName,
      String oldOwner, PrincipalType oldOwnerType, String newOwner,
      PrincipalType newOwnerType, TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public void updateTableOwnerPrivilege(String serverName, String databaseName,
      String tableName, String oldOwner, PrincipalType oldOwnerType, String newOwner,
      PrincipalType newOwnerType, TDdlExecResponse response) throws ImpalaException {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  @Override
  public AuthorizationDelta refreshAuthorization(boolean resetVersions) {
    throw new UnsupportedOperationException(String.format(
        "%s is not supported in Impalad", ClassUtil.getMethodName()));
  }

  /**
   * Validates if the given user is a Sentry admin. The Sentry admin check will make an
   * RPC call to the Catalog server. This check is necessary because some operations
   * in this class does not need to make a call to Sentry, e.g. "show roles" and
   * "show grant" because the authorization data can be retrieved directly from the
   * Impalad catalog without going to Sentry. In order to ensure those operations can
   * only be executed by a Sentry admin, a separate call to the Catalog server is needed
   * to check if the given user is a Sentry admin.
   *
   * @throws AuthorizationException thrown when a given user is not a Sentry admin.
   */
  private static void validateSentryAdmin(String user) throws ImpalaException {
    TSentryAdminCheckRequest request = new TSentryAdminCheckRequest();
    TCatalogServiceRequestHeader header = new TCatalogServiceRequestHeader();
    header.setRequesting_user(user);
    request.setHeader(header);

    byte[] thriftReq = JniUtil.serializeToThrift(request);
    byte[] thriftRes = FeSupport.CheckSentryAdmin(thriftReq);

    TSentryAdminCheckResponse response = new TSentryAdminCheckResponse();
    JniUtil.deserializeThrift(response, thriftRes);

    if (response.getStatus().getStatus_code() != TErrorCode.OK) {
      throw new InternalException(String.format("Error requesting SentryAdminCheck: %s",
          Joiner.on("\n").join(response.getStatus().getError_msgs())));
    }
    if (!response.isIs_admin()) {
      throw new AuthorizationException(String.format("User '%s' does not have " +
          "privileges to access the requested policy metadata.", user));
    }
  }
}
