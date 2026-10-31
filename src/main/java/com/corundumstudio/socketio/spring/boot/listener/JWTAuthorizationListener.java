/*
 * Copyright (c) 2018, hiwepy (https://github.com/hiwepy).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.corundumstudio.socketio.spring.boot.listener;

import org.springframework.util.StringUtils;

import com.corundumstudio.socketio.AuthorizationListener;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.HandshakeData;

/**
 * TODO
 * @author 		： <a href="https://github.com/hiwepy">wandl</a>
 */
public class JWTAuthorizationListener implements AuthorizationListener {

	public static final String AUTHORIZATION_PARAM = "token";
	/**
	 * HTTP Authorization header, equal to <code>Authorization</code>
	 */
	public static final String AUTHORIZATION_HEADER = "X-Authorization";

	private String authorizationHeaderName = AUTHORIZATION_HEADER;
	private String authorizationParamName = AUTHORIZATION_PARAM;
	
	public boolean isAuthorized(HandshakeData data) {
		
		String token = obtainToken(data);

		if (token == null) {
			token = "";
		}

		token = token.trim();
		
		if(StringUtils.hasText(token)) {
			return true;
		}
		
		return false;
	}

	@Override
	public AuthorizationResult getAuthorizationResult(HandshakeData data) {
		return isAuthorized(data) ? AuthorizationResult.SUCCESSFUL_AUTHORIZATION
				: AuthorizationResult.FAILED_AUTHORIZATION;
	}

	protected String obtainToken(HandshakeData data) {
		// 从header中获取token
		String token = data.getHttpHeaders().get(getAuthorizationHeaderName());
		// 如果header中不存在token，则从参数中获取token
		if (StringUtils.isEmpty(token)) {
			return data.getSingleUrlParam(getAuthorizationParamName());
		}
		return token;
	}
	
	public String getAuthorizationHeaderName() {
		return authorizationHeaderName;
	}

	public void setAuthorizationHeaderName(String authorizationHeaderName) {
		this.authorizationHeaderName = authorizationHeaderName;
	}

	public String getAuthorizationParamName() {
		return authorizationParamName;
	}

	public void setAuthorizationParamName(String authorizationParamName) {
		this.authorizationParamName = authorizationParamName;
	}
	
}
