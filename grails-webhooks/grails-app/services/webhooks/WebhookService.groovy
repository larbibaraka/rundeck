package webhooks


import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.config.Features
import com.dtolabs.rundeck.core.event.EventImpl
import com.dtolabs.rundeck.core.event.EventQueryImpl
import com.dtolabs.rundeck.core.event.EventStoreService
import com.dtolabs.rundeck.core.plugins.ValidatedPlugin
import com.dtolabs.rundeck.core.plugins.configuration.PluginAdapterUtility
import com.dtolabs.rundeck.core.plugins.configuration.PluginCustomConfigValidator
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope
import com.dtolabs.rundeck.core.plugins.configuration.Validator
import com.dtolabs.rundeck.core.storage.keys.KeyStorageTree
import com.dtolabs.rundeck.core.webhook.WebhookEventContextImpl
import com.dtolabs.rundeck.plugins.ServiceNameConstants
import com.dtolabs.rundeck.plugins.descriptions.PluginCustomConfig
import com.dtolabs.rundeck.plugins.webhook.DefaultWebhookResponder
import com.dtolabs.rundeck.plugins.webhook.WebhookDataImpl
import com.dtolabs.rundeck.plugins.webhook.WebhookEventContext
import com.dtolabs.rundeck.plugins.webhook.WebhookEventPlugin
import com.fasterxml.jackson.databind.ObjectMapper
import grails.gorm.transactions.Transactional
import groovy.transform.PackageScope
import org.apache.commons.lang.RandomStringUtils
import org.rundeck.app.data.model.v1.AuthenticationToken
import org.rundeck.app.spi.Services
import org.rundeck.app.spi.SimpleServiceProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import webhooks.authenticator.AuthorizationHeaderAuthenticator

import javax.servlet.http.HttpServletRequest

@Transactional
class WebhookService {
    private static final Logger LOGGER = LoggerFactory.getLogger("org.rundeck.webhook.events")
    private static final ObjectMapper mapper = new ObjectMapper()
    private static final String KEY_STORE_PREFIX = "\${KS:"
    private static final String END_MARKER = "}"

    def rundeckPluginRegistry
    def pluginService
    def frameworkService
    def rundeckAuthorizedServicesProvider
    def apiService
    def messageSource
    def userService
    def rundeckAuthTokenManagerService
    def storageService
    def gormEventStoreService
    def featureService

    def processWebhook(String pluginName, String pluginConfigJson, WebhookDataImpl data, UserAndRolesAuthContext authContext, HttpServletRequest request) {
        LOGGER.info("processing '" + data.webhook + "' with plugin '" + pluginName + "' triggered by: '" + authContext.username+ "'")
        Map pluginConfig = pluginConfigJson ? mapper.readValue(pluginConfigJson,HashMap) : [:]
        replaceSecureOpts(authContext,pluginConfig)
        WebhookEventPlugin plugin = pluginService.configurePlugin(pluginName, WebhookEventPlugin.class, frameworkService.getFrameworkPropertyResolver(data.project,pluginConfig),
                                                                  PropertyScope.Instance).instance

        PluginAdapterUtility.setConfig(plugin, pluginConfig)

        plugin.requestHeadersToCopy?.each { hdr -> data.headers[hdr] = request.getHeader(hdr)}

        Services contextServices = rundeckAuthorizedServicesProvider.getServicesWith(authContext)

        if (featureService.featurePresent(Features.EVENT_STORE)) {
            def scopedStore = gormEventStoreService.scoped(
                new Evt(projectName: data.project, subsystem: 'webhooks'),
                new EvtQuery(projectName: data.project, subsystem: 'webhooks')
            )
            contextServices = contextServices.combine(
                    new SimpleServiceProvider([(EventStoreService): scopedStore])
            )
        }
        def keyStorageService = storageService.storageTreeWithContext(authContext)
        contextServices = contextServices.combine(new SimpleServiceProvider([(KeyStorageTree): keyStorageService]))

        WebhookEventContext context = new WebhookEventContextImpl(contextServices)

        return plugin.onEvent(context,data) ?: new DefaultWebhookResponder()
    }

    @PackageScope
    void replaceSecureOpts(UserAndRolesAuthContext authContext, Map configProps) {
        if(configProps.isEmpty()) return
        def keystore = storageService.storageTreeWithContext(authContext)

        Stack<Object> items = []

        configProps.each { idx, i -> items.push([i, configProps, idx]) }

        while(true) {
            if (items.empty())
                break

            def elem = items.pop()

            def (item, parent, index) = elem

            if (item instanceof Map) {
                item.each { idx, i -> items.push([i, item, idx]) }
                continue
            } else if (item instanceof List) {
                item.eachWithIndex { i, idx -> items.push([i, item, idx]) }
                continue
            }

            if (item instanceof String) {
                if(item && item.contains(KEY_STORE_PREFIX)) {
                    String replaced = item
                    int startIdx = -1
                    while(replaced.indexOf(KEY_STORE_PREFIX,startIdx+1) != -1) {
                        startIdx = replaced.indexOf(KEY_STORE_PREFIX)
                        int endIdx = replaced.indexOf(END_MARKER,startIdx)
                        if(endIdx == -1) {
                            log.error("Invalid substitution string, terminating marker not found in value: ${replaced}")
                            break
                        }
                        String valueToReplace = replaced.substring(startIdx,endIdx+1)
                        String keyPath = valueToReplace.substring(KEY_STORE_PREFIX.length(),valueToReplace.length()-1)
                        if(keystore.hasPassword(keyPath)) {
                            String replacementValue = new String(keystore.readPassword(keyPath))
                            replaced = replaced.replace(valueToReplace,replacementValue)
                        } else {
                            log.warn("key was not found in key store: ${keyPath}")
                        }
                    }
                    parent[index] = replaced
                }
            }
        }
    }

    def listWebhooksByProject(String project) {
        Webhook.findAllByProject(project).collect {
            getWebhookWithAuthAsMap(it)
        }
    }

    def saveHook(UserAndRolesAuthContext authContext,def hookData) {
        Webhook hook
        if(hookData.id) {
            hook = Webhook.get(hookData.id)
            if (!hook) return [err: "Webhook not found"]
            if(hookData.roles && !hookData.importData) {
                try {
                    rundeckAuthTokenManagerService.updateAuthRoles(authContext, hook.authToken,rundeckAuthTokenManagerService.parseAuthRoles(hookData.roles))
                } catch (Exception e) {
                    return [err: "Failed to update Auth Token roles: "+e.message]
                }
            }
        } else {
            int countByNameInProject = Webhook.countByNameAndProject(hookData.name,hookData.project)
            if(countByNameInProject > 0) return [err: "A Webhook by that name already exists in this project"]
            String checkUser = hookData.user ?: authContext.username
            if (!hookData.importData && !userService.validateUserExists(checkUser)) return [err: "Webhook user '${checkUser}' not found"]
            hook = new Webhook()
            hook.uuid = UUID.randomUUID().toString()
        }
        hook.uuid = hookData.uuid ?: hook.uuid
        def whsFound = Webhook.findAllByNameAndProjectAndUuidNotEqual(hookData.name, hookData.project, hook.uuid)
        if( whsFound.size() > 0) {
            return [err: " A Webhook by that name already exists in this project"]
        }
        hook.name = hookData.name ?: hook.name
        hook.project = hookData.project ?: hook.project
        String generatedSecureString = null
        if(hookData.useAuth == true && hookData.regenAuth == true) {
            generatedSecureString = RandomStringUtils.random(32, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
            hook.authConfigJson = mapper.writeValueAsString(new AuthorizationHeaderAuthenticator.Config(secret:generatedSecureString.sha256()))
        } else if(hookData.useAuth == false) {
            hook.authConfigJson = null
        }

        if(hookData.enabled != null) hook.enabled = hookData.enabled
        if(hookData.eventPlugin && !pluginService.listPlugins(WebhookEventPlugin).any { it.key == hookData.eventPlugin}){
            hook.discard()
            return [err:"Plugin does not exist: " + hookData.eventPlugin]
        }
        hook.eventPlugin = hookData.eventPlugin ?: hook.eventPlugin

        Map pluginConfig = [:]
        if(hookData.config) pluginConfig = hookData.config instanceof String ? mapper.readValue(hookData.config, HashMap) : hookData.config

        def (ValidatedPlugin vPlugin, boolean isCustom) = validatePluginConfig(hook.eventPlugin,pluginConfig)
        if(!vPlugin.valid) {
            def errMsg = isCustom ?
                    "Validation errors: " + vPlugin.report.errors:
                    "Invalid plugin configuration: " + vPlugin.report.errors.collect { k, v -> "$k : $v" }.join("\n")
            hook.discard()

            return [err: errMsg, errors: vPlugin.report.errors]
        }

        hook.pluginConfigurationJson = mapper.writeValueAsString(pluginConfig)
        Set<String> roles = hookData.roles ? rundeckAuthTokenManagerService.parseAuthRoles(hookData.roles) : authContext.roles
        if((!hook.id || !hook.authToken) && !hookData.shouldImportToken){
            //create token
            String checkUser = hookData.user ?: authContext.username
            try {
                def at=apiService.generateUserToken(authContext, null, checkUser, roles, false,
                                                            AuthenticationToken.AuthTokenType.WEBHOOK)
                hook.authToken = at.token
            } catch (Exception e) {
                hook.discard()
                return [err: "Failed to create associated Auth Token: "+e.message]
            }
        }
        if(hookData.shouldImportToken) {
            if(!importIsAllowed(hook,hookData)){
                throw new Exception("Cannot import webhook: imported auth token does not exist or was changed")
            }
            try {
                rundeckAuthTokenManagerService.importWebhookToken(authContext, hookData.authToken, hookData.user, roles)
            } catch (Exception e) {
                hook.discard()
                return [err: "Failed importing Webhook Token: "+e.message]
            }
            hook.authToken = hookData.authToken
        }

        if(hook.validate()) {
            hook.save(failOnError:true, flush:true)
            def responsePayload = [msg: "Saved webhook"]
            if(generatedSecureString) responsePayload.generatedSecurityString = generatedSecureString
            return responsePayload
        } else {
            if(!hook.id && hook.authToken){
                //delete the created token
                rundeckAuthTokenManagerService.deleteByTokenWithType(hook.authToken, AuthenticationToken.AuthTokenType.WEBHOOK)
            }
            return [err: hook.errors.allErrors.collect { messageSource.getMessage(it,null) }.join(",")]
        }
    }

    boolean importIsAllowed(Webhook hook, Map hookData) {
        if(hook.authToken == hookData.authToken) return true
        if(!hook.authToken
            && Webhook.countByAuthToken(hookData.authToken) == 0
            && !rundeckAuthTokenManagerService.getTokenWithType(
            hookData.authToken,
            AuthenticationToken.AuthTokenType.WEBHOOK
        )) {
            return true
        }
        return false
    }


    @PackageScope
    Tuple2<ValidatedPlugin, Boolean> validatePluginConfig(String webhookPlugin, Map pluginConfig) {
        ValidatedPlugin result = pluginService.validatePluginConfig(ServiceNameConstants.WebhookEvent, webhookPlugin, pluginConfig)
        def isCustom = false
        def plugin = pluginService.getPlugin(webhookPlugin,WebhookEventPlugin.class)
        PluginCustomConfig customConfig = PluginAdapterUtility.getCustomConfigAnnotation(plugin)
        if(customConfig && customConfig.validator()) {
            PluginCustomConfigValidator validator = Validator.createCustomPropertyValidator(customConfig)
            if(validator) {
                result.report.errors.putAll(validator.validate(pluginConfig).errors)
                result.valid = result.report.valid
                isCustom = true
            }
        }
        return new Tuple2(result, isCustom)
    }

    def deleteWebhooksForProject(String project) {
        Webhook.findAllByProject(project).each { webhook ->
            delete(webhook)
        }
    }

    def delete(Webhook hook) {
        String authToken = hook.authToken
        String name = hook.name
        try {
            hook.delete()
            rundeckAuthTokenManagerService.deleteByTokenWithType(authToken, AuthenticationToken.AuthTokenType.WEBHOOK)
            return [msg: "Deleted ${name} webhook"]
        } catch(Exception ex) {
            log.error("delete webhook failed",ex)
            return [err: ex.message]
        }
    }

    def importWebhook(UserAndRolesAuthContext authContext, Map hook, boolean regenAuthTokens) {

        Webhook existing = Webhook.findByUuidAndProject(hook.uuid, hook.project)
        if(existing) hook.id = existing.id
        hook.importData = true

        if(!regenAuthTokens && hook.authToken) {
            hook.shouldImportToken = true
        } else {
            hook.authToken = null
        }

        try {
            def msg = saveHook(authContext, hook)
            if(msg.err) {
                log.error("Failed to import webhook. Error: " + msg.err)
                return [err:"Unable to import webhoook ${hook.name}. Error:"+msg.err]
            }
            return [msg:"Webhook ${hook.name} imported"]
        } catch(Exception ex) {
            log.error("Failed to import webhook", ex)
            return [err:"Unable to import webhoook ${hook.name}. Error:"+ex.message]
        }

    }

    def getWebhookWithAuth(String id) {
        Webhook hook = Webhook.get(id.toLong())
        getWebhookWithAuthAsMap(hook)
    }
    def getWebhookForProjectWithAuth(String id, String project) {
        Webhook hook = getWebhookWithProject(id.toLong(), project)
        if(!hook){
            return null
        }
        getWebhookWithAuthAsMap(hook)
    }

    private Map getWebhookWithAuthAsMap(Webhook hook) {
        AuthenticationToken authToken = rundeckAuthTokenManagerService.getTokenWithType(
            hook.authToken,
            AuthenticationToken.AuthTokenType.WEBHOOK
        )
        return [id:hook.id, uuid:hook.uuid, name:hook.name, project: hook.project, enabled: hook.enabled, user:authToken.ownerName, creator:authToken.creator, roles: authToken.getAuthRolesSet().join(","), authToken:hook.authToken, useAuth: hook.authConfigJson != null, regenAuth: false, eventPlugin:hook.eventPlugin, config:mapper.readValue(hook.pluginConfigurationJson, HashMap)]
    }

    Webhook getWebhook(Long id) {
        return Webhook.get(id)
    }
    Webhook getWebhookWithProject(Long id, String project) {
        return Webhook.findByIdAndProject(id,project)
    }

    Webhook getWebhookByUuid(String uuid) {
        return Webhook.findByUuid(uuid)
    }

    Webhook getWebhookByToken(String token) {
        return Webhook.findByAuthToken(token)
    }

    class Evt extends EventImpl {}

    class EvtQuery extends EventQueryImpl {}
}
