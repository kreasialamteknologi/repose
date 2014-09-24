package features.filters.identitybasicauth

import framework.ReposeValveTest
import framework.mocks.MockIdentityService
import org.apache.commons.codec.binary.Base64
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import org.rackspace.deproxy.Response
import spock.lang.Ignore
import spock.lang.Unroll

import javax.servlet.http.HttpServletResponse
import javax.ws.rs.core.HttpHeaders

/**
 * Created by jennyvo on 9/17/14.
 * Basic Auth filter can't be used alone, have to use with client-auth filter
 */
class BasicAuthStandaloneTest extends ReposeValveTest {
    def static originEndpoint
    def static identityEndpoint
    def static MockIdentityService fakeIdentityService

    def setupSpec() {
        deproxy = new Deproxy()

        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.cleanConfigDirectory()
        repose.configurationProvider.applyConfigs("common", params);
        repose.configurationProvider.applyConfigs("features/filters/identitybasicauth", params);
        repose.configurationProvider.applyConfigs("features/filters/identitybasicauth/onlybasicauth", params);

        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityService = new MockIdentityService(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort, 'identity service', null, fakeIdentityService.handler)
        fakeIdentityService.checkTokenValid = true
    }

    def setup() {
        fakeIdentityService.with {
            // This is required to ensure that one piece of the authentication data is changed
            // so that the cached version in the Akka Client is not used.
            client_apikey = UUID.randomUUID().toString()
            client_token = UUID.randomUUID().toString()
        }
    }

    def cleanupSpec() {
        if (deproxy) {
            deproxy.shutdown()
        }

        if (repose) {
            repose.stop()
        }
    }

    def "when start repose with basic auth, send request without credential"() {
        when: "send request without credential"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET')

        then: "simply pass it on down the filter chain"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.orphanedHandlings.size() == 0
    }

    // NOTE: This would normally be removed by a Header Normalization filter.
    def "Request with X-Auth-Token header sent."() {
        given: "the X-Auth-Token header containing the User Token"
        def headers = [
                "X-Auth-Token": fakeIdentityService.client_token
        ]

        when: "the request already has credentials"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "simply pass it on down the filter chain with out client-aut filter just a pass through"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.orphanedHandlings.size() == 0
        !mc.receivedResponse.headers.findAll(HttpHeaders.WWW_AUTHENTICATE).contains("Basic realm=\"RAX-KEY\"")
    }

    // NOTE: This would normally be removed by a Header Normalization filter.
    def "Request that contains both an X-Auth-Token and HTTP Basic authentication header is sent."() {
        given: "header containing the User Token and an HTTP Basic authentication header (username/apikey)"
        def headers = [
                "X-Auth-Token"             : fakeIdentityService.client_token,
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes)
        ]

        when: "the request already has credentials"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "simply pass it on down the filter chain NOT processing the HTTP Basic authentication"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.orphanedHandlings.size() == 0
        !mc.receivedResponse.headers.findAll(HttpHeaders.WWW_AUTHENTICATE).contains("Basic realm=\"RAX-KEY\"")
    }

    def "Retrieve a token for an HTTP Basic authentication header with UserName/ApiKey"() {
        given: "the HTTP Basic authentication header containing the User Name and API Key"
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes)
        ]

        when: "the request does have an HTTP Basic authentication header with UserName/ApiKey"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "then get a token for it"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.handlings[0].request.headers.getCountByName("X-Auth-Token") == 1
        mc.handlings[0].request.headers.getFirstValue("X-Auth-Token").equals(fakeIdentityService.client_token)
    }

    @Unroll("Sending request with invalid UserName #userName and API Key #apiKey pair.")
    def "Fail to retrieve a token for an HTTP Basic authentication header with an invalid UserName/ApiKey pair"() {
        given: "the HTTP Basic authentication header containing the User Name and API Key"
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((userName + ":" + apiKey).bytes)
        ]

        when: "the request does have an HTTP Basic authentication header with UserName/ApiKey"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "Request reject if invalid apikey or username"
        mc.receivedResponse.code == HttpServletResponse.SC_UNAUTHORIZED.toString()
        mc.handlings.size() == 0
        mc.receivedResponse.getHeaders().findAll(HttpHeaders.WWW_AUTHENTICATE).contains("Basic realm=\"RAX-KEY\"")

        where:
        userName                            | apiKey
        fakeIdentityService.client_username | "BAD-API-KEY"
        "BAD-USER-NAME"                     | fakeIdentityService.client_apikey
        "BAD-USER-NAME"                     | "BAD-API-KEY"
    }

    @Ignore
    // Only the first AUTHORIZATION Basic header will be processed.
    def "Stop trying to retrieve a token for an HTTP Basic authentication header after a token has been obtained."() {
        given: "the HTTP Basic authentication header containing the User Name and API Key"
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + "BAD-API-KEY").bytes),
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes),
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString(("BAD-USER-NAME" + ":" + fakeIdentityService.client_apikey).bytes)
        ]

        when: "the request does have an HTTP Basic authentication header with UserName/ApiKey"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "then get a token for it"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.handlings[0].request.headers.getCountByName("X-Auth-Token") == 1
        mc.handlings[0].request.headers.getFirstValue("X-Auth-Token").equals(fakeIdentityService.client_token)
    }

    def "Ensure that subsequent calls within the cache timeout are retrieving the token from the cache"() {
        given: "the HTTP Basic authentication header containing the User Name and API Key"
        sleep(3000) //wait for sometime for any previous cache same token
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes)
        ]

        when: "multiple requests that have the same HTTP Basic authentication header"
        MessageChain mc0 = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)
        MessageChain mc1 = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "get the token from the cache"
        mc0.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc0.handlings[0].request.headers.getCountByName("X-Auth-Token") == 1
        mc0.handlings[0].request.headers.getFirstValue("X-Auth-Token").equals(fakeIdentityService.client_token)
        mc0.orphanedHandlings.size() == 1
        mc1.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc1.handlings[0].request.headers.getCountByName("X-Auth-Token") == 1
        mc1.handlings[0].request.headers.getFirstValue("X-Auth-Token").equals(fakeIdentityService.client_token)
        mc1.orphanedHandlings.size() == 0
    }

    def "Ensure that subsequent calls outside the cache timeout are retrieving a new token not from the cache"() {
        given: "the HTTP Basic authentication header containing the User Name and API Key"
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes)
        ]

        when: "multiple requests that have the same HTTP Basic authentication header"
        sleep(3000) //wait for sometime for any previous cache same token
        MessageChain mc0 = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)
        sleep 3000 // How do I get this programmatically from the config.
        MessageChain mc1 = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "get the token from the Identity (Keystone) service"
        mc0.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc0.handlings[0].request.headers.getCountByName("X-Auth-Token") == 1
        mc0.handlings[0].request.headers.getFirstValue("X-Auth-Token").equals(fakeIdentityService.client_token)
        mc0.orphanedHandlings.size() == 1
        mc1.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc1.handlings[0].request.headers.getCountByName("X-Auth-Token") == 1
        mc1.handlings[0].request.headers.getFirstValue("X-Auth-Token").equals(fakeIdentityService.client_token)
        mc1.orphanedHandlings.size() == 1
    }

    // NOTE: This would normally be removed by a Header Normalization filter.
    def "when start repose with basic auth only, x-auth-token should work"() {
        given:
        def headers = [
                "X-Auth-Token": fakeIdentityService.client_token
        ]

        when: "send request without credential"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "request should pass as no basic auth filter"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.orphanedHandlings.size() == 0
        !mc.receivedResponse.getHeaders().findAll(HttpHeaders.WWW_AUTHENTICATE).contains("Basic realm=\"RAX-KEY\"")
    }

    def "when send request with credential"() {
        def headers = [
                (HttpHeaders.AUTHORIZATION): 'Basic ' + Base64.encodeBase64URLSafeString((fakeIdentityService.client_username + ":" + fakeIdentityService.client_apikey).bytes)
        ]

        when: "the request does have an HTTP Basic authentication header"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: headers)

        then: "request should pass as no basic auth filter"
        mc.receivedResponse.code == HttpServletResponse.SC_OK.toString()
        mc.handlings.size() == 1
        mc.handlings[0].request.headers.contains(HttpHeaders.AUTHORIZATION)
        mc.handlings[0].request.headers.contains("X-Auth-Token")
        mc.orphanedHandlings.size() == 1 // This is the call to the Mock Identity service through deproxy.
        !mc.receivedResponse.getHeaders().findAll(HttpHeaders.WWW_AUTHENTICATE).contains("Basic realm=\"RAX-KEY\"")
    }

    def "Inject header WWW-authenticate when basicauth or other component failed with 401"() {
        when: "the request sends with invalid key"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET',
                defaultHandler: { new Response(HttpServletResponse.SC_UNAUTHORIZED, null, null, null) })

        then: "request should pass as no basic auth filter"
        mc.receivedResponse.code == HttpServletResponse.SC_UNAUTHORIZED.toString()
        mc.handlings.size() == 1
        mc.orphanedHandlings.size() == 0
        mc.receivedResponse.getHeaders().findAll(HttpHeaders.WWW_AUTHENTICATE).contains("Basic realm=\"RAX-KEY\"")
    }

    def "Log a very loud WARNING stating the OpenStack Basic Auth filter cannot be used alone."() {
        expect: "check for the WARNING."
        reposeLogSearch.searchByString("WARNING").size() > 0
    }
}
