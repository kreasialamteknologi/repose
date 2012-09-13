package com.rackspace.auth.rackspace;

import com.rackspace.auth.AuthGroups;
import com.rackspace.auth.AuthToken;
import com.rackspace.auth.FullAuthInfo;
import com.rackspace.papi.commons.util.regex.ExtractorResult;

/**
 * @author fran
 */
public interface AuthenticationService {
   FullAuthInfo validateToken(ExtractorResult<String> account, String userToken);
   AuthGroups getGroups(String userName);
}
