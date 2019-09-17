package eportfolium.com.karuta.business.impl;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.passay.CharacterRule;
import org.passay.EnglishCharacterData;
import org.passay.PasswordGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import eportfolium.com.karuta.business.contract.EmailManager;
import eportfolium.com.karuta.business.contract.SecurityManager;
import eportfolium.com.karuta.consumer.contract.dao.ConfigurationDao;
import eportfolium.com.karuta.consumer.contract.dao.CredentialDao;
import eportfolium.com.karuta.consumer.contract.dao.CredentialSubstitutionDao;
import eportfolium.com.karuta.consumer.contract.dao.GroupUserDao;
import eportfolium.com.karuta.consumer.util.DomUtils;
import eportfolium.com.karuta.model.bean.Credential;
import eportfolium.com.karuta.model.bean.CredentialSubstitution;
import eportfolium.com.karuta.model.bean.CredentialSubstitutionId;
import eportfolium.com.karuta.model.exception.AuthenticationException;
import eportfolium.com.karuta.model.exception.BusinessException;
import eportfolium.com.karuta.model.exception.GenericBusinessException;
import eportfolium.com.karuta.model.exception.ValueRequiredException;
import eportfolium.com.karuta.util.StringUtil;
import eportfolium.com.karuta.util.ValidateUtil;

/**
 * @author mlengagne
 *
 */
@Service
public class SecurityManagerImpl implements SecurityManager {

	@Autowired
	private CredentialDao credentialDao;

	@Autowired
	private CredentialSubstitutionDao credentialSubstitutionDao;

	@Autowired
	private GroupUserDao groupUserDao;

	@Autowired
	private ConfigurationDao configurationDao;

	@Autowired
	private EmailManager emailManager;

	/**
	 * Each token produced by this class uses this identifier as a prefix.
	 */
	public static final String ID = "$31$";

	/**
	 * The minimum recommended cost, used by default
	 */
	public static final int DEFAULT_COST = 16;

	private static final String ALGORITHM = "PBKDF2WithHmacSHA512";

	/**
	 * A keyLength of 256 would be safer :).
	 */
	private static final int SIZE = 128;

	private static final Pattern layout = Pattern.compile("\\$31\\$(\\d\\d?)\\$(.{43})");

	private final SecureRandom random;

	private final int cost;

	private static final Log log = LogFactory.getLog(SecurityManagerImpl.class);

	private SecurityManagerImpl() {
		this(DEFAULT_COST);
	}

	/**
	 * Create a SecurityManager with a specified cost
	 * 
	 * @param cost the exponential computational cost of hashing a password, 0 to 30
	 */
	private SecurityManagerImpl(int cost) {
		iterations(cost); /* Validate cost */
		this.cost = cost;
		this.random = new SecureRandom();
	}

	/**
	 * The token generated is stored at the server, and should be associated with
	 * the user identity. For example, a user table with id, login name and/or email
	 * address, and token. When someone logs in with login and password, lookup the
	 * stored token with the login and pass that to the authenticate() method with
	 * the password.
	 */
	public boolean changePassword(String username, String password) {
		boolean changed = false;
		try {
			Credential credential = credentialDao.getByLogin(username, null);
			setPassword(password, credential);
			credentialDao.merge(credential);
			changed = true;
		} catch (BusinessException e) {
			e.printStackTrace();
		}

		return changed;
	}

	/**
	 * This method provides a way for users to change their own userPassword.
	 * 
	 * @param userId
	 * @param currentPassword
	 * @param newPassword
	 * @throws BusinessException
	 */
	public void changeUserPassword(Long userId, String currentPassword, String newPassword) throws BusinessException {
		Credential user = credentialDao.findById(userId);

		if (!authenticate(currentPassword.toCharArray(), user.getPassword())) {
			throw new AuthenticationException("User_password_incorrect");
		}

		if (user.getPassword() != null && authenticate(newPassword.toCharArray(), user.getPassword())) {
			throw new GenericBusinessException("User_newpassword_is_same");
		}
		setPassword(newPassword, user);
		credentialDao.merge(user);
	}

	public void changeCustomer(Credential user) throws BusinessException {
		Credential c = credentialDao.merge(user);

		// If id is different it means the person did not exist so merge has created a
		// new one.
		if (!c.getId().equals(user.getId())) {
			throw new eportfolium.com.karuta.model.exception.DoesNotExistException(Credential.class, user.getId());
		}
	}

	public boolean registerUser(String username, String password) {
		boolean isRegistered = false;

		if (!credentialDao.userExists(username)) {
			Credential newUser = new Credential();
			newUser.setLogin(username);
			try {
				setPassword(password, newUser);
				newUser.setDisplayFirstname("");
				newUser.setDisplayLastname("");
				newUser.setIsDesigner(Integer.valueOf(1));
				// Insert user
				credentialDao.persist(newUser);
				isRegistered = true;
			} catch (BusinessException e) {
				e.printStackTrace();
			}
		}
		return isRegistered;
	}

	public Long createUser(String username, String email) throws BusinessException {

		if (!ValidateUtil.isEmail(email)) {
			throw new IllegalArgumentException();
		}

		final Credential cr = new Credential();
		cr.setLogin(username);
		cr.setDisplayFirstname(username);
		cr.setDisplayLastname("");
		cr.setEmail(email);
		/// Credential checking use hashing, we'll never reach this.
		setPassword(generatePassword(), cr);
		credentialDao.persist(cr);
		// DO NOT return the whole User, because its UserPassword is present in it
		return cr.getId();
	}

	/// Generate password
	public String generatePassword2() throws NoSuchAlgorithmException {
		long base = System.currentTimeMillis();
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		byte[] output = md.digest(Long.toString(base).getBytes());
		String password = String.format("%032X", new BigInteger(1, output));
		password = password.substring(0, 9);
		return password;
	}

	public String generatePassword() {
		List<CharacterRule> rules = Arrays.asList(
				// at least one upper-case character
				new CharacterRule(EnglishCharacterData.UpperCase, 1),

				// at least one lower-case character
				new CharacterRule(EnglishCharacterData.LowerCase, 1),

				// at least one digit character
				new CharacterRule(EnglishCharacterData.Digit, 1),

				// at least one symbol (special character)
				new CharacterRule(EnglishCharacterData.Special, 1));

		PasswordGenerator generator = new PasswordGenerator();

		// Generated password is 12 characters long, which complies with policy
		String password = generator.generatePassword(12, rules);
		return password;
	}

	/**
	 * Do all tests of userPassword size, content, history, etc. here...
	 * 
	 * @param newPassword
	 * @param customer
	 */
	private void setPassword(String newPassword, Credential credential) throws BusinessException {

		if (StringUtil.isEmpty(newPassword)) {
			throw new ValueRequiredException(credential, "User_newpassword_is_required");
		}

		credential.setPassword(hash(newPassword.toCharArray()));
	}

	private static int iterations(int cost) {
		if ((cost < 0) || (cost > 31))
			throw new IllegalArgumentException("cost: " + cost);
		return 1 << cost;
	}

	/**
	 * Hash a password for storage. *
	 * 
	 * <p>
	 * Passwords should be stored in a {@code char[]} so that it can be filled with
	 * zeros after use instead of lingering on the heap and elsewhere.
	 * 
	 * @return a secure authentication token to be stored for later authentication
	 */
	private String hash(char[] password) {
		byte[] salt = new byte[SIZE / 8];
		random.nextBytes(salt);
		byte[] dk = pbkdf2(password, salt, 1 << cost);
		byte[] hash = new byte[salt.length + dk.length];
		System.arraycopy(salt, 0, hash, 0, salt.length);
		System.arraycopy(dk, 0, hash, salt.length, dk.length);
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		return ID + cost + '$' + enc.encodeToString(hash);
	}

	/**
	 * Authenticate with a password and a stored password token.
	 * 
	 * @return true if the password and token match
	 */
	private boolean authenticate(char[] password, String token) {
		Matcher m = layout.matcher(token);
		if (!m.matches())
			throw new IllegalArgumentException("Invalid token format");
		int iterations = iterations(Integer.parseInt(m.group(1)));
		byte[] hash = Base64.getUrlDecoder().decode(m.group(2));
		byte[] salt = Arrays.copyOfRange(hash, 0, SIZE / 8);
		byte[] check = pbkdf2(password, salt, iterations);
		int zero = 0;
		for (int idx = 0; idx < check.length; ++idx)
			zero |= hash[salt.length + idx] ^ check[idx];
		return zero == 0;
	}

	private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
		KeySpec spec = new PBEKeySpec(password, salt, iterations, SIZE);
		try {
			SecretKeyFactory f = SecretKeyFactory.getInstance(ALGORITHM);
			return f.generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Missing algorithm: " + ALGORITHM, ex);
		} catch (InvalidKeySpecException ex) {
			throw new IllegalStateException("Invalid SecretKeyFactory", ex);
		}
	}

	public int deleteCredential(Long userId) throws BusinessException {
		if (!credentialDao.isAdmin(userId))
			throw new GenericBusinessException("Status.FORBIDDEN : No admin right");

		int res = credentialDao.updateCredentialToken(userId, null);
		return res;
	}

	public int deleteUsers(Long userId, Long groupId) {
		int result = 0;

		try {
			credentialDao.removeById(userId);
			groupUserDao.removeById(groupId);

		} catch (Exception e) {
			e.printStackTrace();
			result = 1;
		}
		return result;
	}

	public String postUsers(String in, Long userId) throws Exception {
		if (!credentialDao.isAdmin(userId) && !credentialDao.isCreator(userId))
			throw new GenericBusinessException("Status.FORBIDDEN : No admin right");

		String result = null;
		Credential cr = null;

		String password = null;
		String firstname = null;
		String lastname = null;
		String email = null;
		String designerstr = null;
		String active = null;
		String substitute = null;
		String other = "";
		Long id = 0L;
		NodeList children2 = null;
		Node item = null;
		String nodeName = null;

		// On recupere le body
		Document doc;

		doc = DomUtils.xmlString2Document(in, new StringBuffer());
		Element users = doc.getDocumentElement();

		NodeList children = null;

		children = users.getChildNodes();
		// On parcourt une premiere fois les enfants pour recuperer la liste et ecrire
		// en base

		// On verifie le bon format
		StringBuilder userdone = new StringBuilder();
		userdone.append("<users>");
		String username = null;
		try {
			if (users.getNodeName().equals("users")) {
				for (int i = 0; i < children.getLength(); i++) {
					if (children.item(i).getNodeName().equals("user")) {

						children2 = children.item(i).getChildNodes();
						for (int j = 0; j < children2.getLength(); j++) {
							item = children2.item(j);
							nodeName = item.getNodeName();
							if (nodeName.equals("username")) {
								username = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("password")) {
								password = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("firstname")) {
								firstname = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("lastname")) {
								lastname = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("email")) {
								email = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("active")) {
								active = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("designer")) {
								designerstr = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("substitute")) {
								substitute = DomUtils.getInnerXml(item);
							} else if (nodeName.equals("other")) {
								other = DomUtils.getInnerXml(item);
							}
						}

						cr = new Credential();
						cr.setLogin(username);
						cr.setDisplayFirstname(StringUtils.defaultString(firstname));
						cr.setDisplayLastname(StringUtils.defaultString(lastname));
						cr.setEmail(StringUtils.defaultString(email));
						setPassword(password, cr);
						try {
							cr.setActive(StringUtils.isNotEmpty(active) ? Integer.valueOf(active) : 1);
						} catch (NumberFormatException e) {
							cr.setActive(Integer.valueOf(1));
						}

						if ("1".equals(designerstr))
							cr.setIsDesigner(1);
						else
							cr.setIsDesigner(0);
						cr.setOther(other);

						// On ajoute l'utilisateur dans la base de donnees
						credentialDao.persist(cr);
						id = cr.getId();

						if (substitute != null) {
							CredentialSubstitution subst = new CredentialSubstitution();
							/// FIXME: More complete rule to use
							CredentialSubstitutionId csId = new CredentialSubstitutionId();
							// id=0, don't check who this person can substitute (except root)
							csId.setId(0L);
							csId.setCredential(cr);
							csId.setType("USER");

							if ("1".equals(substitute)) {
								subst.setId(csId);
								credentialSubstitutionDao.persist(subst);
							} else if ("0".equals(substitute)) {
								subst = credentialSubstitutionDao.findById(csId);
								credentialSubstitutionDao.remove(subst);
							}
						} else
							substitute = "0";

						userdone.append("<user ").append("id=\"").append(id).append("\">");
						userdone.append("<username>").append(username).append("</username>");
						userdone.append("<firstname>").append(firstname).append("</firstname>");
						userdone.append("<lastname>").append(lastname).append("</lastname>");
						userdone.append("<email>").append(email).append("</email>");
						userdone.append("<active>").append(active).append("</active>");
						userdone.append("<designer>").append(designerstr).append("</designer>");
						userdone.append("<substitute>").append(substitute).append("</substitute>");
						userdone.append("<other>").append(substitute).append("</other>");
						userdone.append("</user>");
					}
				}
			} else {
				result = "Missing \"users\" tag";
			}
		} catch (Exception e) {
			log.error(e.getMessage());
			result = "Error when processing user: " + username;
		}
		userdone.append("</users>");

		if (result == null)
			result = userdone.toString();

		return result;
	}

	public boolean createUser(String username, String email, boolean isDesigner, long userId) throws Exception {
		if (!credentialDao.isAdmin(userId) && !credentialDao.isCreator(userId))
			throw new GenericBusinessException("Status.FORBIDDEN : No admin right");

		boolean isRegistered = false;

		if (!credentialDao.userExists(username)) {

			try {
				Credential newUser = new Credential();
				String passwd = generatePassword();
				/// Credential checking use hashing, we'll never reach this.
				setPassword(passwd, newUser);
				newUser.setLogin(username);
				newUser.setEmail(email);
				newUser.setActive(1);
				newUser.setDisplayFirstname("");
				newUser.setDisplayLastname("");
				newUser.setIsDesigner(BooleanUtils.toInteger(isDesigner));
				// Insert user
				credentialDao.persist(newUser);
				isRegistered = true;

				final Map<String, String> template_vars = new HashMap<String, String>();
				template_vars.put("firstname", username);
				template_vars.put("lastname", "");
				template_vars.put("email", email);
				template_vars.put("passwd", passwd);

				// Send email
				final Integer langId = Integer.valueOf(configurationDao.get("PS_LANG_DEFAULT"));
				try {
					emailManager.send(langId, "account", emailManager.getTranslation("Welcome!"), template_vars, email,
							username);
				} catch (Exception e) {
					e.printStackTrace();
				}
			} catch (BusinessException e) {
				e.printStackTrace();
			}
		}
		return isRegistered;
	}

	public String userChangeInfo(Long userId, Long userId2, String in) throws BusinessException {
		if (userId != userId2)
			throw new GenericBusinessException("Not authorized");

		String result1 = null;
		String originalp = null;
		String password = null;
		String email = null;
		String firstname = null;
		String lastname = null;

		// Parse input
		Document doc;
		Element infUser = null;
		try {
			doc = DomUtils.xmlString2Document(in, new StringBuffer());
			infUser = doc.getDocumentElement();
		} catch (Exception e) {
			e.printStackTrace();
		}

		NodeList children = infUser.getChildNodes();

		if (infUser.getNodeName().equals("user")) {

			/// Get parameters
			for (int y = 0; y < children.getLength(); y++) {
				if (children.item(y).getNodeName().equals("prevpass")) {
					originalp = DomUtils.getInnerXml(children.item(y));
				} else if (children.item(y).getNodeName().equals("password")) {
					password = DomUtils.getInnerXml(children.item(y));
				} else if (children.item(y).getNodeName().equals("email")) {
					email = DomUtils.getInnerXml(children.item(y));
				} else if (children.item(y).getNodeName().equals("firstname")) {
					firstname = DomUtils.getInnerXml(children.item(y));
				} else if (children.item(y).getNodeName().equals("lastname")) {
					lastname = DomUtils.getInnerXml(children.item(y));
				}
			}

			changeUserPassword(userId, originalp, password);
//			if (securityLog != null) {
//			securityLog.write(String.format("[%s] Changed password\n", username));
//			securityLog.flush();
//		}

			try {
				Credential cr = credentialDao.findById(userId);
				if (email != null) {
					cr.setEmail(email);
				}
				if (firstname != null) {
					cr.setDisplayFirstname(firstname);
				}
				if (lastname != null) {
					cr.setDisplayLastname(lastname);
				}
				credentialDao.merge(cr);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		result1 = "" + userId2;
		return result1;
	}

	public boolean isAdmin(Long id) {
		return credentialDao.isAdmin(id);
	}

	public boolean isCreator(Long id) {
		return credentialDao.isCreator(id);
	}

	/**
	 * Check if customer password is the right one
	 *
	 * @param passwd Password
	 * @return bool result
	 */
	public boolean checkPassword(Long userID, String passwd) {
		if (userID == null || !ValidateUtil.isUnsignedId(userID.intValue()) || !ValidateUtil.isPasswd(passwd)) {
			log.error("Fatal Error : illegal checkPassword parameters");
			throw new RuntimeException();
		}

		Credential cr = credentialDao.getActiveByUserId(userID);
		return cr != null ? authenticate(passwd.toCharArray(), cr.getPassword()) : false;
	}

	public String putInfUser(Long userId, long userId2, String in) throws BusinessException {

		String result1 = null;
		String originalp = null;
		String password = null;
		String email = null;
		String username = null;
		String firstname = null;
		String lastname = null;
		String active = null;
		String is_admin = null;
		String is_designer = null;
		String hasSubstitute = null;
		String other = "";

		// On recupere le body
		Document doc;
		Element infUser = null;
		try {
			doc = DomUtils.xmlString2Document(in, new StringBuffer());
			infUser = doc.getDocumentElement();
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (infUser.getNodeName().equals("user")) {
			// On recupere les attributs
			NodeList children = infUser.getChildNodes();
			/// Fetch parameters
			/// TODO Make some function out of this I think
			for (int y = 0; y < children.getLength(); y++) {
				if (children.item(y).getNodeName().equals("username")) {
					username = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("prevpass")) {
					originalp = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("password")) {
					password = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("firstname")) {
					firstname = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("lastname")) {
					lastname = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("email")) {
					email = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("admin")) {
					is_admin = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("designer")) {
					is_designer = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("active")) {
					active = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("substitute")) {
					hasSubstitute = DomUtils.getInnerXml(children.item(y));
				}
				if (children.item(y).getNodeName().equals("other")) {
					other = DomUtils.getInnerXml(children.item(y));
				}
			}

			/// Check if user has the correct password to execute changes
			boolean isOK = checkPassword(userId, originalp);

			/// Send queries
			if (isOK || credentialDao.isAdmin(userId)) {
				Credential user = credentialDao.findById(userId2);

				if (username != null) {
					user.setLogin(username);
				}
				if (password != null) {
					setPassword(password, user);
				}
				if (firstname != null) {
					user.setDisplayFirstname(firstname);
				}
				if (lastname != null) {
					user.setDisplayLastname(lastname);
				}
				if (email != null) {
					user.setEmail(email);
				}
				if (is_admin != null) {
					int is_adminInt = 0;
					if ("1".equals(is_admin))
						is_adminInt = 1;

					user.setIsAdmin(is_adminInt);
				}
				if (is_designer != null) {
					int is_designerInt = 0;
					if ("1".equals(is_designer))
						is_designerInt = 1;

					user.setIsDesigner(is_designerInt);
				}
				if (active != null) {
					int activeInt = 0;
					if ("1".equals(active))
						activeInt = 1;

					user.setActive(activeInt);
				}
				if (other != null) {
					user.setOther(other);
				}
				credentialDao.merge(user);

				if (hasSubstitute != null) {
					CredentialSubstitution subst = new CredentialSubstitution();
					/// FIXME: More complete rule to use
					CredentialSubstitutionId csId = new CredentialSubstitutionId();
					// id=0, don't check who this person can substitute (except root)
					csId.setId(0L);
					csId.setCredential(user);
					csId.setType("USER");

					if ("1".equals(hasSubstitute)) {
						subst.setId(csId);
						credentialSubstitutionDao.persist(subst);
					} else if ("0".equals(hasSubstitute)) {
						subst = credentialSubstitutionDao.findById(csId);
						credentialSubstitutionDao.remove(subst);
					}
				}
			} else {
				throw new GenericBusinessException("Not authorized");
			}
		}

		result1 = "" + userId2;

		return result1;
	}

}