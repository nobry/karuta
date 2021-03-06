/* =======================================================
	Copyright 2020 - ePortfolium - Licensed under the
	Educational Community License, Version 2.0 (the "License"); you may
	not use this file except in compliance with the License. You may
	obtain a copy of the License at

	http://www.osedu.org/licenses/ECL-2.0

	Unless required by applicable law or agreed to in writing,
	software distributed under the License is distributed on an "AS IS"
	BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
	or implied. See the License for the specific language governing
	permissions and limitations under the License.
   ======================================================= */

package eportfolium.com.karuta.webapp.rest.resource;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.CookieParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogConfigurationException;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import edu.yale.its.tp.cas.client.ServiceTicketValidator;
import eportfolium.com.karuta.business.contract.ConfigurationManager;
import eportfolium.com.karuta.business.contract.EmailManager;
import eportfolium.com.karuta.business.contract.SecurityManager;
import eportfolium.com.karuta.business.contract.UserManager;
import eportfolium.com.karuta.config.Consts;
import eportfolium.com.karuta.model.exception.BusinessException;
import eportfolium.com.karuta.util.StrToTime;
import eportfolium.com.karuta.webapp.annotation.InjectLogger;
import eportfolium.com.karuta.webapp.eventbus.KEvent;
import eportfolium.com.karuta.webapp.rest.provider.mapper.exception.RestWebApplicationException;
import eportfolium.com.karuta.webapp.util.DomUtils;

@Path("/credential")
public class CredentialResource extends AbstractResource {

	@Autowired
	private UserManager userManager;

	@Autowired
	private EmailManager emailManager;

	@Autowired
	private SecurityManager securityManager;

	@Autowired
	private ConfigurationManager configurationManager;

	@InjectLogger
	private static Logger logger;

	/**
	 * Fetch current user info. <br>
	 * GET /rest/api/credential
	 * 
	 * @param user
	 * @param token
	 * @param groupId
	 * @param sc
	 * @param httpServletRequest
	 * @return <user id="uid"> <username></username> <firstname></firstname>
	 *         <lastname></lastname> <email></email> <admin>1/0</admin>
	 *         <designer>1/0</designer> <active>1/0</active>
	 *         <substitute>1/0</substitute> </user>
	 */

	@Path("/test")
	@GET
	public String getHello() {
		return "Bonjour :)";
	}

	@GET
	@Produces(MediaType.APPLICATION_XML)
	public Response getCredential(@CookieParam("user") String user, @CookieParam("credential") String token,
			@QueryParam("group") int groupId, @Context ServletConfig sc,
			@Context HttpServletRequest httpServletRequest) {
		UserInfo ui = checkCredential(httpServletRequest, user, token, null);

		if (ui.userId == 0) // userid not valid -- id de l'utilisateur non valide.
		{
			return Response.status(401).build();
		}

		try {
			String xmluser = userManager.getUserInfos(ui.userId);

			/// Add shibboleth info if needed
			HttpSession session = httpServletRequest.getSession(false);
			Integer fromshibe = (Integer) session.getAttribute("fromshibe");
			Integer updatefromshibe = (Integer) session.getAttribute("updatefromshibe");
			String alist = configurationManager.get("shib_attrib");
			HashMap<String, String> updatevals = new HashMap<String, String>();

			if (fromshibe != null && fromshibe == 1 && alist != null) {
				/// Fetch and construct needed data
				String[] attriblist = alist.split(",");
				int lastst = xmluser.lastIndexOf("<");
				StringBuilder shibuilder = new StringBuilder(xmluser.substring(0, lastst));

				for (String attrib : attriblist) {
					String value = (String) httpServletRequest.getAttribute(attrib);
					shibuilder.append("<").append(attrib).append(">").append(value).append("</").append(attrib)
							.append(">");
					/// Pre-process values
					if (1 == updatefromshibe) {
						String colname = configurationManager.get(attrib);
						updatevals.put(colname, value);
					}
				}
				/// Update values
				if (1 == updatefromshibe) {
					String xmlInfUser = String.format(
							"<user id=\"%s\">" + "<firstname>%s</firstname>" + "<lastname>%s</lastname>"
									+ "<email>%s</email>" + "</user>",
							ui.userId, updatevals.get("display_firstname"), updatevals.get("display_lastname"),
							updatevals.get("email"));
					/// User update its own info automatically
					securityManager.changeUser(ui.userId, ui.userId, xmlInfUser);
					/// Consider it done
					session.removeAttribute("updatefromshibe");
				}
				/// Add it as last tag after "moving" the closing tag
				shibuilder.append(xmluser.substring(lastst));
				xmluser = shibuilder.toString();
			}
			return Response.ok(xmluser).build();
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RestWebApplicationException(Status.INTERNAL_SERVER_ERROR, ex.getMessage());
		}
	}

	/**
	 * Send login information. <br>
	 * PUT /rest/api/credential/login
	 * 
	 * @param xmlCredential
	 * @param user
	 * @param token
	 * @param groupId
	 * @param sc
	 * @param httpServletRequest
	 * @return
	 */
	@Path("/login")
	@PUT
	@Produces(MediaType.APPLICATION_XML)
	@Consumes(MediaType.APPLICATION_XML)
	public Response putCredentialFromXml(String xmlCredential, @CookieParam("user") String user,
			@CookieParam("credential") String token, @QueryParam("group") int groupId, @Context ServletConfig sc,
			@Context HttpServletRequest httpServletRequest) {
		return this.postCredentialFromXml(xmlCredential, user, token, 0, sc, httpServletRequest);
	}

	/**
	 * Send login information. <br>
	 * POST /rest/api/credential/login
	 * 
	 * @param xmlCredential
	 * @param user
	 * @param token
	 * @param groupId
	 * @param sc
	 * @param httpServletRequest
	 * @return
	 */
	@Path("/login")
	@POST
	@Produces(MediaType.APPLICATION_XML)
	@Consumes(MediaType.APPLICATION_XML)
	public Response postCredentialFromXml(String xmlCredential, @CookieParam("user") String user,
			@CookieParam("credential") String token, @QueryParam("group") int groupId, @Context ServletConfig sc,
			@Context HttpServletRequest httpServletRequest) {
		HttpSession session = httpServletRequest.getSession(true);
		KEvent event = new KEvent();
		event.eventType = KEvent.EventType.LOGIN;
		event.inputData = xmlCredential;
		String retVal = "";
		int status = 0;

		String authlog = configurationManager.get("auth_log");
		Log authLog = null;
		try {
			if (!"".equals(authlog) && authlog != null)
				authLog = LogFactory.getLog(authlog);
		} catch (LogConfigurationException e1) {
			logger.error("Could not create authentification log file");
		}

		try {
			Document doc = DomUtils.xmlString2Document(xmlCredential, new StringBuffer());
			Element credentialElement = doc.getDocumentElement();
			String login = "";
			String password = "";
			String substit = null;
			if (credentialElement.getNodeName().equals("credential")) {
				String[] templogin = DomUtils.getInnerXml(doc.getElementsByTagName("login").item(0)).split("#");
				password = DomUtils.getInnerXml(doc.getElementsByTagName("password").item(0));

				if (templogin.length > 1)
					substit = templogin[1];
				login = templogin[0];
			}

			String[] resultCredential = securityManager.postCredentialFromXml(login, password, substit);
			// 0 : XML de retour
			// 1,2 : username, uid
			// 3,4 : substitute name, substitute id
			if (resultCredential == null) {
				event.status = 403;
				retVal = "invalid credential";

				if (authLog != null)
					authLog.info(String.format("Authentication error for user '%s' date '%s'\n", login,
							StrToTime.convert("now")));
			} else if (!"0".equals(resultCredential[2])) {
				// String tokenID = resultCredential[2];

				if (substit != null && !"0".equals(resultCredential[4])) {
					long uid = Long.parseLong(resultCredential[2]);
					long subid = Long.parseLong(resultCredential[4]);

					session.setAttribute("user", resultCredential[3]);
					session.setAttribute("uid", subid);
					session.setAttribute("subuser", resultCredential[1]);
					session.setAttribute("subuid", uid);
					if (authLog != null)
						authLog.info(String.format("Authentication success for user '%s' date '%s' (Substitution)\n",
								login, StrToTime.convert("now")));
				} else {
					String login1 = resultCredential[1];
					long userId = Long.parseLong(resultCredential[2]);
					long subuid = 0;

					session.setAttribute("user", login1);
					session.setAttribute("uid", userId);
					session.setAttribute("subuser", "");
					session.setAttribute("subuid", subuid);
					if (authLog != null)
						authLog.info(String.format("Authentication success for user '%s' date '%s'\n", login,
								StrToTime.convert("now")));
				}

				event.status = 200;
				retVal = resultCredential[0];
			}
			// eventbus.processEvent(event);

			return Response.status(event.status).entity(retVal).type(event.mediaType).build();
		} catch (RestWebApplicationException ex) {
			ex.printStackTrace();
			logger.error(ex.getLocalizedMessage());
			throw new RestWebApplicationException(Status.FORBIDDEN, "invalid Credential or invalid group member");
		} catch (Exception ex) {
			status = 500;
			retVal = ex.getMessage();
			logger.error(ex.getMessage());
		}

		return Response.status(status).entity(retVal).type(MediaType.APPLICATION_XML).build();
	}

	/**
	 * Tell system to forgot your password. <br>
	 * POST /rest/api/credential/forgot
	 * 
	 * @param xml
	 * @param sc
	 * @param httpServletRequest
	 * @return
	 */
	@Path("/forgot")
	@POST
	@Consumes(MediaType.APPLICATION_XML)
	public Response postForgotCredential(String xml, @Context ServletConfig sc,
			@Context HttpServletRequest httpServletRequest) {
		int retVal = 404;
		String retText = "";
		Logger securityLog = null;
		String securitylog = configurationManager.get("security_log");

		if (StringUtils.isNotEmpty(securitylog))
			securityLog = LoggerFactory.getLogger(securitylog);

		String resetEnable = configurationManager.get("enable_password_reset");
		if (resetEnable != null
				&& ("y".equals(resetEnable.toLowerCase()) || "true".equals(resetEnable.toLowerCase()))) {

			try {
				Document doc = DomUtils.xmlString2Document(xml, new StringBuffer());
				Element userInfos = doc.getDocumentElement();

				String username = "";
				if (userInfos.getNodeName().equals("credential")) {
					NodeList children2 = userInfos.getChildNodes();
					for (int y = 0; y < children2.getLength(); y++) {
						if (children2.item(y).getNodeName().equals("login")) {
							username = DomUtils.getInnerXml(children2.item(y));
							break;
						}
					}
				}

				// Vérifier si nous avons cet email enregistré en base
				String email = userManager.getEmailByLogin(username);
				if (email != null && !"".equals(email)) {

					// écrire les changements en base
					String password = securityManager.generatePassword();
					boolean result = securityManager.changePassword(username, password);

					if (result) {
						if (securityLog != null) {
							String ip = httpServletRequest.getRemoteAddr();
							securityLog.info(String.format(
									"[%s] [%s] a demandé la réinitialisation de son mot de passe\n", ip, username));
						}

						final Map<String, String> template_vars = new HashMap<String, String>();
						template_vars.put("firstname", username);
						template_vars.put("lastname", "");
						template_vars.put("email", email);
						template_vars.put("passwd", password);

						String cc_email = configurationManager.get("sys_email");
						// Envoie d'un email
						final Integer langId = Integer.valueOf(configurationManager.get("PS_LANG_DEFAULT"));
						emailManager.send(langId, "employee_password",
								emailManager.getTranslation("Your new password!"), template_vars, email, username, null,
								null, null, null, Consts._PS_MAIL_DIR_, false, cc_email, null);

						retVal = 200;
						retText = "sent";
					}
				}
			} catch (BusinessException ex) {
				ex.printStackTrace();
				logger.error(ex.getLocalizedMessage());
				throw new RestWebApplicationException(Status.FORBIDDEN, "invalid Credential or invalid group member");
			} catch (Exception ex) {
				logger.error(ex.getMessage());
				ex.printStackTrace();
				throw new RestWebApplicationException(Status.INTERNAL_SERVER_ERROR, ex.getMessage());
			}
		}
		return Response.status(retVal).entity(retText).build();
	}

	/**
	 * Fetch current user information (CAS). <br>
	 * GET /rest/api/credential/login/cas
	 * 
	 * @param content
	 * @param user
	 * @param token
	 * @param groupId
	 * @param ticket
	 * @param redir
	 * @param sc
	 * @param httpServletRequest
	 * @return
	 */
	@POST
	@Path("/login/cas")
	public Response postCredentialFromCas(String content, @CookieParam("user") String user,
			@CookieParam("credential") String token, @QueryParam("group") int groupId,
			@QueryParam("ticket") String ticket, @QueryParam("redir") String redir, @Context ServletConfig sc,
			@Context HttpServletRequest httpServletRequest) {
		return getCredentialFromCas(user, token, groupId, ticket, redir, sc, httpServletRequest);
	}

	@GET
	@Path("/login/cas")
	public Response getCredentialFromCas(@CookieParam("user") String user, @CookieParam("credential") String token,
			@QueryParam("group") int groupId, @QueryParam("ticket") String ticket, @QueryParam("redir") String redir,
			@Context ServletConfig sc, @Context HttpServletRequest httpServletRequest) {

		HttpSession session = httpServletRequest.getSession(true); // FIXME
		String xmlResponse = null;
		String userId = null;
		String completeURL;
		StringBuffer requestURL;
		String casUrlValidation = configurationManager.get("casUrlValidation");

		try {
			ServiceTicketValidator sv = new ServiceTicketValidator();

			if (casUrlValidation == null) {
				Response response = null;
				try {
					// formulate the response
					response = Response.status(Status.PRECONDITION_FAILED).entity("CAS URL not defined").build();
				} catch (Exception e) {
					response = Response.status(500).build();
				}
				return response;
			}

			sv.setCasValidateUrl(casUrlValidation);

			/// X-Forwarded-Proto is for certain setup, check config file
			/// for some more details
			String proto = httpServletRequest.getHeader("X-Forwarded-Proto");
			requestURL = httpServletRequest.getRequestURL();
			if (proto == null) {
				System.out.println("cas usuel");
				if (redir != null) {
					requestURL.append("?redir=").append(redir);
				}
				completeURL = requestURL.toString();
			} else {
				/// Keep only redir parameter
				if (redir != null) {
					requestURL.append("?redir=").append(redir);
				}
				completeURL = requestURL.replace(0, requestURL.indexOf(":"), proto).toString();
			}
			/// completeURL should be the same provided in the "service" parameter
			// System.out.println(String.format("Service: %s\n", completeURL));

			sv.setService(completeURL);
			sv.setServiceTicket(ticket);
			// sv.setProxyCallbackUrl(urlOfProxyCallbackServlet);
			sv.validate();

			xmlResponse = sv.getResponse();
			if (xmlResponse.contains("cas:authenticationFailure")) {
				System.out.println(String.format("CAS response: %s\n", xmlResponse));
				return Response.status(Status.FORBIDDEN).entity("CAS error").build();
			}

			// <cas:user>vassoilm</cas:user>
			// session.setAttribute("user", sv.getUser());
			// session.setAttribute("uid", dataProvider.getUserId(sv.getUser()));
			userId = String.valueOf(userManager.getUserId(sv.getUser(), null));
			if (userId != null) {
				session.setAttribute("user", sv.getUser()); // FIXME
				session.setAttribute("uid", Integer.parseInt(userId)); // FIXME
			} else {
				return Response.status(403)
						.entity("Login " + sv.getUser() + " not found or bad CAS auth (bad ticket or bad url service : "
								+ completeURL + ") : " + sv.getErrorMessage())
						.build();
			}

			Response response = null;
			try {
				// formulate the response
				response = Response.status(201).header("Location", redir)
						.entity("<script>document.location.replace('" + redir + "')</script>").build();
			} catch (Exception e) {
				response = Response.status(500).build();
			}
			return response;
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RestWebApplicationException(Status.FORBIDDEN,
					"Vous n'avez pas les droits necessaires (ticket ?, casUrlValidation) :" + casUrlValidation);
		}
	}

	/**
	 * Ask to logout, clear session. <br>
	 * POST /rest/api/credential/logout
	 * 
	 * @param sc
	 * @param httpServletRequest
	 * @return
	 */
	@Path("/logout")
	@POST
	public Response logout(@Context ServletConfig sc, @Context HttpServletRequest httpServletRequest) {
		HttpSession session = httpServletRequest.getSession(false);
		if (session != null)
			session.invalidate();
		return Response.ok("logout").build();
	}

}
