/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityConditionList;
import org.ofbiz.entity.condition.EntityExpr;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtil;

/**
 * An implementation of the Security interface that uses the OFBiz database
 * for permission storage.
 */
@SuppressWarnings("deprecation")
public class OFBizSecurity implements Security {

    public static final String module = OFBizSecurity.class.getName();

    protected Delegator delegator = null;

    protected static final Map<String, Map<String, String>> simpleRoleEntity = UtilMisc.toMap(
        "ORDERMGR", UtilMisc.<String, String>toMap("name", "OrderRole", "pkey", "orderId"),
        "FACILITY", UtilMisc.<String, String>toMap("name", "FacilityParty", "pkey", "facilityId"),
        "MARKETING", UtilMisc.<String, String>toMap("name", "MarketingCampaignRole", "pkey", "marketingCampaignId"));

    protected OFBizSecurity() {}

    protected OFBizSecurity(Delegator delegator) {
        this.delegator = delegator;
    }

    @Override
    public Delegator getDelegator() {
        return this.delegator;
    }

    @Override
    public void setDelegator(Delegator delegator) {
        this.delegator = delegator;
    }

    @Override
    public Iterator<GenericValue> findUserLoginSecurityGroupByUserLoginId(String userLoginId) {
        try {
            List<GenericValue> collection = EntityUtil.filterByDate(delegator.findByAnd("UserLoginSecurityGroup", UtilMisc.toMap("userLoginId", userLoginId), null, true));
            return collection.iterator();
        } catch (GenericEntityException e) {
            Debug.logWarning(e, module);
            return Collections.<GenericValue>emptyList().iterator();
        }
    }

    @Override
    public boolean securityGroupPermissionExists(String groupId, String permission) {
        try {
            return delegator.findOne("SecurityGroupPermission",  UtilMisc.toMap("groupId", groupId, "permissionId", permission), true) != null;
        } catch (GenericEntityException e) {
            Debug.logWarning(e, module);
            return false;
        }
    }

    @Override
    public boolean hasPermission(String permission, HttpSession session) {
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");

        if (userLogin == null) return false;

        return hasPermission(permission, userLogin);
    }

    @Override
    public boolean hasPermission(String permission, GenericValue userLogin) {
        if (userLogin == null) return false;

        Iterator<GenericValue> iterator = findUserLoginSecurityGroupByUserLoginId(userLogin.getString("userLoginId"));
        GenericValue userLoginSecurityGroup = null;

        while (iterator.hasNext()) {
            userLoginSecurityGroup = iterator.next();
            if (securityGroupPermissionExists(userLoginSecurityGroup.getString("groupId"), permission)) return true;
        }

        return false;
    }

    @Override
    public boolean hasEntityPermission(String entity, String action, HttpSession session) {
        if (session == null) {
            return false;
        }
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        if (userLogin == null) {
            return false;
        }
        return hasEntityPermission(entity, action, userLogin);
    }

    @Override
    public boolean hasEntityPermission(String entity, String action, GenericValue userLogin) {
        if (userLogin == null) return false;
        String permission = entity.concat(action);
        String adminPermission = entity.concat("_ADMIN");

        // if (Debug.infoOn()) Debug.logInfo("hasEntityPermission: entity=" + entity + ", action=" + action, module);
        Iterator<GenericValue> iterator = findUserLoginSecurityGroupByUserLoginId(userLogin.getString("userLoginId"));
        GenericValue userLoginSecurityGroup = null;

        while (iterator.hasNext()) {
            userLoginSecurityGroup = iterator.next();
            if (securityGroupPermissionExists(userLoginSecurityGroup.getString("groupId"), permission))
                return true;
            if (securityGroupPermissionExists(userLoginSecurityGroup.getString("groupId"), adminPermission))
                return true;
        }

        return false;
    }

    @Override
    public boolean hasRolePermission(String application, String action, String primaryKey, String role, HttpSession session) {
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        return hasRolePermission(application, action, primaryKey, role, userLogin);
    }

    @Override
    public boolean hasRolePermission(String application, String action, String primaryKey, String role, GenericValue userLogin) {
        List<String> roles = null;
        if (role != null && !role.equals(""))
            roles = UtilMisc.toList(role);
        return hasRolePermission(application, action, primaryKey, roles, userLogin);
    }

    @Override
    public boolean hasRolePermission(String application, String action, String primaryKey, List<String> roles, HttpSession session) {
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        return hasRolePermission(application, action, primaryKey, roles, userLogin);
    }

    @Override
    public boolean hasRolePermission(String application, String action, String primaryKey, List<String> roles, GenericValue userLogin) {
        String entityName = null;
        EntityCondition condition = null;

        if (userLogin == null)
            return false;

        // quick test for special cases where were just want to check the permission (find screens)
        if (primaryKey.equals("") && roles == null) {
            if (hasEntityPermission(application, action, userLogin)) return true;
            if (hasEntityPermission(application + "_ROLE", action, userLogin)) return true;
        }

        Map<String, String> simpleRoleMap = OFBizSecurity.simpleRoleEntity.get(application);
        if (simpleRoleMap != null && roles != null) {
            entityName = simpleRoleMap.get("name");
            String pkey = simpleRoleMap.get("pkey");
            if (pkey != null) {
                List<EntityExpr> expressions = new ArrayList<EntityExpr>();
                for (String role: roles) {
                    expressions.add(EntityCondition.makeCondition("roleTypeId", EntityOperator.EQUALS, role));
                }
                EntityConditionList<EntityExpr> exprList = EntityCondition.makeCondition(expressions, EntityOperator.OR);
                EntityExpr keyExpr = EntityCondition.makeCondition(pkey, primaryKey);
                EntityExpr partyExpr = EntityCondition.makeCondition("partyId", userLogin.getString("partyId"));
                condition = EntityCondition.makeCondition(exprList, keyExpr, partyExpr);
            }

        }

        return hasRolePermission(application, action, entityName, condition, userLogin);
    }

    /**
     * Like hasEntityPermission above, this checks the specified action, as well as for "_ADMIN" to allow for simplified
     * general administration permission, but also checks action_ROLE and validates the user is a member for the
     * application.
     *
     * @param application The name of the application corresponding to the desired permission.
     * @param action The action on the application corresponding to the desired permission.
     * @param entityName The name of the role entity to use for validation.
     * @param condition EntityCondition used to query the entityName.
     * @param userLogin The userLogin object for user to check against.
     * @return Returns true if the currently logged in userLogin has the specified permission, otherwise returns false.
     */
    private boolean hasRolePermission(String application, String action, String entityName, EntityCondition condition, GenericValue userLogin) {
        if (userLogin == null) return false;

        // first check the standard permission
        if (hasEntityPermission(application, action, userLogin)) return true;

        // make sure we have what's needed for role security
        if (entityName == null || condition == null) return false;

        // now check the user for the role permission
        if (hasEntityPermission(application + "_ROLE", action, userLogin)) {
            // we have the permission now, we check to make sure we are allowed access
            List<GenericValue> roleTest = null;
            try {
                //Debug.logInfo("Doing Role Security Check on [" + entityName + "]" + "using [" + condition + "]", module);
                roleTest = delegator.findList(entityName, condition, null, null, null, false);
            } catch (GenericEntityException e) {
                Debug.logError(e, "Problems doing role security lookup on entity [" + entityName + "] using [" + condition + "]", module);
                return false;
            }

            // if we pass all tests
            //Debug.logInfo("Found (" + (roleTest == null ? 0 : roleTest.size()) + ") matches :: " + roleTest, module);
            if (UtilValidate.isNotEmpty(roleTest)) return true;
        }

        return false;
    }

    @Override
    public void clearUserData(GenericValue userLogin) {
        if (userLogin != null) {
            delegator.getCache().remove("UserLoginSecurityGroup", EntityCondition.makeCondition("userLoginId", EntityOperator.EQUALS, userLogin.getString("userLoginId")));
        }
    }

}
