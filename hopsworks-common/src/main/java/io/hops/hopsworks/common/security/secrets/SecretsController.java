/*
 * This file is part of Hopsworks
 * Copyright (C) 2019, Logical Clocks AB. All rights reserved
 *
 * Hopsworks is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package io.hops.hopsworks.common.security.secrets;

import com.google.common.base.Strings;
import io.hops.hopsworks.common.dao.project.ProjectFacade;
import io.hops.hopsworks.common.dao.project.team.ProjectTeamFacade;
import io.hops.hopsworks.common.dao.user.UserFacade;
import io.hops.hopsworks.common.dao.user.security.secrets.SecretPlaintext;
import io.hops.hopsworks.common.dao.user.security.secrets.SecretsFacade;
import io.hops.hopsworks.common.security.CertificatesMgmService;
import io.hops.hopsworks.common.security.SymmetricEncryptionDescriptor;
import io.hops.hopsworks.common.security.SymmetricEncryptionService;
import io.hops.hopsworks.common.util.DateUtils;
import io.hops.hopsworks.exceptions.ProjectException;
import io.hops.hopsworks.exceptions.ServiceException;
import io.hops.hopsworks.exceptions.UserException;
import io.hops.hopsworks.persistence.entity.project.Project;
import io.hops.hopsworks.persistence.entity.project.team.ProjectTeam;
import io.hops.hopsworks.persistence.entity.user.Users;
import io.hops.hopsworks.persistence.entity.user.security.secrets.Secret;
import io.hops.hopsworks.persistence.entity.user.security.secrets.SecretId;
import io.hops.hopsworks.persistence.entity.user.security.secrets.VisibilityType;
import io.hops.hopsworks.restutils.RESTCodes;

import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Stateless
@TransactionAttribute(TransactionAttributeType.SUPPORTS)
/**
 * Stateless bean for managing Secrets of users
 * Secrets are encrypted with Hopsworks master encryption password
 * before persisted in the database.
 */
public class SecretsController {
  private static final Logger LOG = Logger.getLogger(SecretsController.class.getName());
  
  @EJB
  private SecretsFacade secretsFacade;
  @EJB
  private SymmetricEncryptionService symmetricEncryptionService;
  @EJB
  private CertificatesMgmService certificatesMgmService;
  @EJB
  private UserFacade userFacade;
  @EJB
  private ProjectFacade projectFacade;
  @EJB
  private ProjectTeamFacade projectTeamFacade;
  
  /**
   * Adds a new Secret. The secret is encrypted before persisted in the database.
   * It throws an exception if a Secret with the same name already exists for the
   * same user.
   *
   * @param user User to add the Secret
   * @param secretName Identifier of the secret
   * @param secret The secret itself
   * @param visibilityType Visibility of a Secret. It can be private or shared among members of a project
   * @throws UserException
   */
  public Secret add(Users user, String secretName, String secret, VisibilityType visibilityType, Integer projectIdScope)
      throws UserException {
    SecretId secretId = new SecretId(user.getUid(), secretName);
    if(secretsFacade.findById(secretId) != null) {
      throw new UserException(RESTCodes.UserErrorCode.SECRET_EXISTS, Level.FINE,
        "Secret already exists", "Secret with name " + secretName + " already exists for user " + user.getUsername());
    }
    Secret storedSecret = validateAndCreateSecret(secretId, user, secret, visibilityType, projectIdScope);
    secretsFacade.persist(storedSecret);
    return storedSecret;
  }

  /**
   * Adds a new Secret. The secret is encrypted before persisted in the database.
   * If a secret with the same name already exists for the user, it updates it.
   *
   * @param user
   * @param secretName
   * @param secretStr
   * @param visibilityType
   * @param projectIdScope
   * @return
   */
  @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
  public Secret addOrUpdate(Users user, String secretName, String secretStr,
                            VisibilityType visibilityType, Integer projectIdScope) throws UserException {
    SecretId secretId = new SecretId(user.getUid(), secretName);
    Secret secret = secretsFacade.findById(secretId);
    if (secret != null) {
      Secret generatedSecret = validateAndCreateSecret(secretId, user, secretStr, visibilityType, projectIdScope);
      secret.setSecret(generatedSecret.getSecret());
      secret.setAddedOn(generatedSecret.getAddedOn());
      secret.setVisibilityType(generatedSecret.getVisibilityType());
      secret.setProjectIdScope(generatedSecret.getProjectIdScope());
    } else {
      secret = validateAndCreateSecret(secretId, user, secretStr, visibilityType, projectIdScope);
    }

    secretsFacade.persist(secret);
    return secret;
  }

  /**
   *
   * @param user
   * @param secretName
   * @param secret
   * @param projectIdScope
   * @return
   * @throws UserException
   */
  public Secret createSecretForProject(Users user, String secretName, String secret, Integer projectIdScope)
    throws UserException, ProjectException {
    Project project = projectFacade.find(projectIdScope);
    if (project == null) {
      throw new ProjectException(RESTCodes.ProjectErrorCode.PROJECT_NOT_FOUND, Level.FINE,
        "Project with ID " + projectIdScope + " does not exist!",
        "User " + user.getUsername() + " requested shared Secret " + secretName +
          " but Project with ID " + projectIdScope + "does not exist");
    }
    if (!projectTeamFacade.isUserMemberOfProject(project, user)) {
      throw new ProjectException(RESTCodes.ProjectErrorCode.TEAM_MEMBER_NOT_FOUND, Level.FINE, "User not a member of " +
        "project with ID " + projectIdScope +".");
    }
    SecretId secretId = new SecretId(user.getUid(), secretName);
    if(secretsFacade.findById(secretId) != null) {
      throw new UserException(RESTCodes.UserErrorCode.SECRET_EXISTS, Level.FINE,
        "Secret already exists", "Secret with name " + secretName + " already exists for user " + user.getUsername());
    }
    return validateAndCreateSecret(secretId, user, secret, VisibilityType.PROJECT, projectIdScope);
  }

  /**
   * Validates parameters required to create a secret and creates the secret
   *
   * @param secretId combination of userId and secretName
   * @param user
   * @param secret in plain text
   * @param visibilityType
   * @param projectIdScope
   * @return created secret object
   * @throws UserException
   */
  public Secret validateAndCreateSecret(SecretId secretId, Users user, String secret, VisibilityType visibilityType,
                                        Integer projectIdScope) throws UserException{
    checkIfUserIsNull(user);
    checkIfNameIsNullOrEmpty(secretId.getName());
    if (Strings.isNullOrEmpty(secretId.getName()) || Strings.isNullOrEmpty(secret)) {
      throw new UserException(RESTCodes.UserErrorCode.SECRET_EMPTY, Level.FINE,
        "Secret value is either null or empty", "Secret name or value is empty or null");
    }
    try {
      Secret storedSecret = new Secret(secretId, encryptSecret(secret),
              DateUtils.localDateTime2Date(DateUtils.getNow()));
      storedSecret.setVisibilityType(visibilityType);
      if (visibilityType.equals(VisibilityType.PRIVATE)) {
        // When the user adds secrets without closing the UI modal
        // they might change visibility to Private but a Project from
        // the previous attempt is still selected
        storedSecret.setProjectIdScope(null);
      } else {
        if (projectIdScope == null) {
          throw new UserException(RESTCodes.UserErrorCode.SECRET_EMPTY, Level.FINE,
            "Secret visibility is PROJECT but there is not Project ID scope",
            "Project scope for shared secret " + secretId.getName() + " is null");
        }
        if (projectFacade.find(projectIdScope) == null) {
          throw new UserException(RESTCodes.UserErrorCode.SECRET_EMPTY, Level.FINE,
            "Could not find a project for Project ID scope " + projectIdScope);
        }
        storedSecret.setProjectIdScope(projectIdScope);
      }
      return storedSecret;
    } catch (IOException | GeneralSecurityException ex) {
      throw new UserException(RESTCodes.UserErrorCode.SECRET_ENCRYPTION_ERROR, Level.SEVERE,
        "Error encrypting secret", "Could not encrypt Secret " + secretId.getName(), ex);
    }
  }

  /**
   * Gets all Secrets' names associated with a user. The actual secret is not
   * returned, nor decrypted.
   *
   * @param user The user to fetch the Secrets for
   * @return A view of all Secret names associated with the user
   * @throws UserException
   */
  public List<SecretPlaintext> getAllForUser(Users user) throws UserException {
    checkIfUserIsNull(user);
    List<Secret> secrets = secretsFacade.findAllForUser(user);
    return secrets.stream()
        .map(c -> constructSecretView(user, c))
        .collect(Collectors.toList());
  }
  
  /**
   * Deletes a Secret associated with a user. It does NOT throw an exception if
   * the secret does not exist
   *
   * @param user The user who owns the key
   * @param secretName The name of the Secret
   * @throws UserException
   */
  public void delete(Users user, String secretName) throws UserException {
    checkIfUserIsNull(user);
    checkIfNameIsNullOrEmpty(secretName);
    SecretId secretId = new SecretId(user.getUid(), secretName);
    try {
      secretsFacade.deleteSecret(secretId);
    } catch (EJBException de) {
      Throwable rootCause = getRootCause(de);
      if (rootCause instanceof SQLIntegrityConstraintViolationException) {
        throw new UserException(RESTCodes.UserErrorCode.SECRET_DELETION_FAILED, Level.FINE, "Cannot delete secret. " +
          "Secret is in use by a connector. Try deleting the connector first. ", rootCause.getMessage());
      } else {
        throw de;
      }
    }
  }

  private Throwable getRootCause(Throwable throwable) {
    Throwable rootCause = throwable;
    while (throwable != null) {
      rootCause = throwable;
      throwable = throwable.getCause();
    }
    return rootCause;
  }
  
  /**
   * Deletes all Secrets associated with a user
   *
   * @param user User who owns the keys
   * @throws UserException
   */
  public void deleteAll(Users user) throws UserException {
    checkIfUserIsNull(user);
    try {
      secretsFacade.deleteSecretsForUser(user);
    } catch (EJBException de) {
      Throwable rootCause = getRootCause(de);
      if (rootCause instanceof SQLIntegrityConstraintViolationException) {
        throw new UserException(RESTCodes.UserErrorCode.SECRET_DELETION_FAILED, Level.FINE, "Cannot delete secrets. " +
          "One or more secrets are in use by a connector. Try deleting the connectors first. ", rootCause.getMessage());
      } else {
        throw de;
      }
    }
  }
  
  /**
   * Get all Secrets that exist in the system encrypted.
   * It is used for handling a Hopsworks master encryption password change
   * @return A list with all Secrets in the system encrypted
   */
  public List<Secret> getAllCiphered() {
    return secretsFacade.findAll();
  }
  
  /**
   * Gets a decrypted Secret
   * @param user The user associated with the secret
   * @param secretName The Secret identifier
   * @return The Secret decrypted along with some metadata
   * @throws UserException
   */
  public SecretPlaintext get(Users user, String secretName) throws UserException {
    checkIfUserIsNull(user);
    checkIfNameIsNullOrEmpty(secretName);
    SecretId id = new SecretId(user.getUid(), secretName);
    Secret storedSecret = secretsFacade.findById(id);
    checkIfSecretIsNull(storedSecret, secretName, user);
    try {
      return decrypt(user, storedSecret);
    } catch (IOException | GeneralSecurityException ex) {
      throw new UserException(RESTCodes.UserErrorCode.SECRET_ENCRYPTION_ERROR, Level.SEVERE,
          "Error decrypting Secret", "Could not decrypt Secret " + secretName, ex);
    }
  }

  /**
   * Gets a decrypted shared secret depending on its Visibility. It will throw an exception
   * if the Visibility was set to PRIVATE or the caller is not member of the Project
   * the Secret is shared with.
   *
   * @param caller The user who requested the Secret
   * @param ownerUsername the username of the user owning the secret
   * @param secretName Identifier of the Secret
   * @return The decrypted Secret
   * @throws UserException
   * @throws ServiceException
   * @throws ProjectException
   */
  public SecretPlaintext getShared(Users caller, String ownerUsername, String secretName)
      throws UserException, ServiceException, ProjectException {
    Users ownerUser = userFacade.findByUsername(ownerUsername);
    return getShared(caller, ownerUser, secretName);
  }

  /**
   * Gets a decrypted shared secret depending on its Visibility. It will throw an exception
   * if the Visibility was set to PRIVATE or the caller is not member of the Project
   * the Secret is shared with.
   *
   * @param caller The user who requested the Secret
   * @param ownerUser the user owner of the secret
   * @param secretName Identifier of the Secret
   * @return The decrypted Secret
   * @throws UserException
   * @throws ServiceException
   * @throws ProjectException
   */
  public SecretPlaintext getShared(Users caller, Users ownerUser, String secretName)
      throws UserException, ServiceException, ProjectException {
    checkIfUserIsNull(caller);
    checkIfNameIsNullOrEmpty(secretName);
    checkIfUserIsNull(ownerUser);
    
    Secret storedSecret = secretsFacade.findById(new SecretId(ownerUser.getUid(), secretName));
    checkIfSecretIsNull(storedSecret, secretName, ownerUser);
    if (storedSecret.getVisibilityType() == null || storedSecret.getVisibilityType().equals(VisibilityType.PRIVATE)) {
      throw new UserException(RESTCodes.UserErrorCode.ACCESS_CONTROL, Level.FINE,
          "Secret is Private", "User " + caller.getUsername() + " requested PRIVATE secret <" + ownerUser.getUid()
          + ", " + secretName + ">");
    }
    
    Integer projectId = storedSecret.getProjectIdScope();
    if (projectId == null) {
      throw new ServiceException(RESTCodes.ServiceErrorCode.SERVICE_GENERIC_ERROR, Level.WARNING,
          "Visibility's Project ID is empty",
          "Secret " + secretName + " visibility is PROJECT but Project ID is null");
    }
    Project project = projectFacade.find(projectId);
    if (project == null) {
      throw new ProjectException(RESTCodes.ProjectErrorCode.PROJECT_NOT_FOUND, Level.FINE,
          "Project with ID " + projectId + " does not exist!",
          "User " + caller.getUsername() + " requested shared Secret " + secretName +
          " but Project with ID " + projectId + "does not exist");
    }
    // Check if caller is member of the Project
    for (ProjectTeam projectTeam : project.getProjectTeamCollection()) {
      if (caller.getUid().equals(projectTeam.getUser().getUid())) {
        try {
          return decrypt(ownerUser, storedSecret);
        } catch (IOException | GeneralSecurityException ex) {
          throw new UserException(RESTCodes.UserErrorCode.SECRET_ENCRYPTION_ERROR, Level.SEVERE,
              "Error decrypting Secret", "Could not decrypt Secret " + secretName, ex);
        }
      }
    }
    // Check if caller is a member of some shared project
    throw new UserException(RESTCodes.UserErrorCode.ACCESS_CONTROL, Level.FINE,
        "Not authorized to access Secret " + secretName,
        "User " + caller.getUsername() + " tried to access shared Secret " + secretName
        + " but they are not member of Project " + project.getName());
  }
  
  private void checkIfUserIsNull(Users user) throws UserException {
    if (user == null) {
      throw new UserException(RESTCodes.UserErrorCode.USER_DOES_NOT_EXIST, Level.FINE);
    }
  }
  
  private void checkIfNameIsNullOrEmpty(String name) throws UserException {
    if (Strings.isNullOrEmpty(name)) {
      throw new UserException(RESTCodes.UserErrorCode.SECRET_EMPTY, Level.FINE,
          "Secret is either null or empty", "Secret name or key is empty or null");
    }
  }
  
  private void checkIfSecretIsNull(Secret secret, String secretName, Users user) throws UserException {
    if (secret == null) {
      throw new UserException(RESTCodes.UserErrorCode.SECRET_EMPTY, Level.FINE,
          "Could not find Secret for user",
          "Could not find Secret with name " + secretName + " for user " + user.getUsername());
    }
  }
  
  /**
   * Constructs a Secret view without the actual secret
   *
   * @param user
   * @param ciphered
   * @return
   */
  private SecretPlaintext constructSecretView(Users user, Secret ciphered) {
    return SecretPlaintext.newInstance(user, ciphered.getId().getName(), "", ciphered.getAddedOn(),
        ciphered.getVisibilityType(), ciphered.getProjectIdScope());
  }
  
  /**
   * Decrypts an encrypted Secret
   *
   * @param user
   * @param ciphered
   * @return
   * @throws IOException
   * @throws GeneralSecurityException
   */
  private SecretPlaintext decrypt(Users user, Secret ciphered)
      throws IOException, GeneralSecurityException {
    String password = certificatesMgmService.getMasterEncryptionPassword();
  
    // [salt(64),iv(12),payload)]
    byte[][] split = symmetricEncryptionService.splitPayloadFromCryptoPrimitives(ciphered.getSecret());
    
    SymmetricEncryptionDescriptor descriptor = new SymmetricEncryptionDescriptor.Builder()
        .setPassword(password)
        .setSalt(split[0])
        .setIV(split[1])
        .setInput(split[2])
        .build();
    descriptor = symmetricEncryptionService.decrypt(descriptor);
    
    byte[] plaintext = descriptor.getOutput();

    return SecretPlaintext.newInstance(user, ciphered.getId().getName(), bytes2string(plaintext),
        ciphered.getAddedOn(), ciphered.getVisibilityType(), ciphered.getProjectIdScope());
  }
  
  /**
   * Encrypts a Secret.
   *
   * @param secret
   * @return Encrypted secret along with cryptographic primitives. The structure is the following:
   * Salt(64 bytes), InitializationVector(12 bytes), EncryptedPayload
   * @throws IOException
   * @throws GeneralSecurityException
   */
  public byte[] encryptSecret(String secret) throws IOException, GeneralSecurityException {
    String password = certificatesMgmService.getMasterEncryptionPassword();
    SymmetricEncryptionDescriptor descriptor = new SymmetricEncryptionDescriptor.Builder()
        .setInput(string2bytes(secret))
        .setPassword(password)
        .build();
    descriptor = symmetricEncryptionService.encrypt(descriptor);
    
    return symmetricEncryptionService.mergePayloadWithCryptoPrimitives(descriptor.getSalt(), descriptor.getIv(),
        descriptor.getOutput());
  }
  
  /**
   * Utility method to convert a String to byte array
   * using the system's default charset
   *
   * @param str
   * @return
   */
  private byte[] string2bytes(String str) {
    return str.getBytes(Charset.defaultCharset());
  }
  
  /**
   * Utility method to convert a byte array to String
   * using the system's default charset
   * @param bytes
   * @return
   */
  private String bytes2string(byte[] bytes) {
    return new String(bytes, Charset.defaultCharset());
  }

  @TransactionAttribute(TransactionAttributeType.SUPPORTS)
  public void checkCanAccessSecret(Secret secret, Users user) throws ProjectException {
    if (secret != null &&
      !projectTeamFacade.isUserMemberOfProject(projectFacade.find(secret.getProjectIdScope()), user)) {
      throw new ProjectException(RESTCodes.ProjectErrorCode.TEAM_MEMBER_NOT_FOUND, Level.FINE,
        "User not a member of project with ID " + secret.getProjectIdScope() +
          ". Can not delete secret in project with id " + secret.getProjectIdScope());
    }
  }
}
