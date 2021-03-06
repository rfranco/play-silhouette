/**
 * Copyright 2015 Mohiva Organisation (license at mohiva dot com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mohiva.play.silhouette.impl.authenticators

import com.mohiva.play.silhouette._
import com.mohiva.play.silhouette.api.exceptions._
import com.mohiva.play.silhouette.api.services.{ AuthenticatorResult, AuthenticatorService }
import com.mohiva.play.silhouette.api.services.AuthenticatorService._
import com.mohiva.play.silhouette.api.util.{ Clock, IDGenerator }
import com.mohiva.play.silhouette.api.{ Logger, LoginInfo, StorableAuthenticator }
import com.mohiva.play.silhouette.impl.authenticators.BearerTokenAuthenticatorService._
import com.mohiva.play.silhouette.impl.daos.AuthenticatorDAO
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ RequestHeader, Result }

import scala.concurrent.Future
import scala.util.Try

/**
 * An authenticator that uses a header based approach with the help of a bearer token. It
 * works by transporting a token in a user defined header to track the authenticated user
 * and a server side backing store that maps the token to an authenticator instance.
 *
 * The authenticator can use sliding window expiration. This means that the authenticator times
 * out after a certain time if it wasn't used. This can be controlled with the [[idleTimeout]]
 * property.
 *
 * Note: If deploying to multiple nodes the backing store will need to synchronize.
 *
 * @param id The authenticator ID.
 * @param loginInfo The linked login info for an identity.
 * @param lastUsedDate The last used timestamp.
 * @param expirationDate The expiration time.
 * @param idleTimeout The time in seconds an authenticator can be idle before it timed out.
 */
case class BearerTokenAuthenticator(
  id: String,
  loginInfo: LoginInfo,
  lastUsedDate: DateTime,
  expirationDate: DateTime,
  idleTimeout: Option[Int])
  extends StorableAuthenticator {

  /**
   * The Type of the generated value an authenticator will be serialized to.
   */
  type Value = String

  /**
   * Checks if the authenticator isn't expired and isn't timed out.
   *
   * @return True if the authenticator isn't expired and isn't timed out.
   */
  def isValid = !isExpired && !isTimedOut

  /**
   * Checks if the authenticator is expired. This is an absolute timeout since the creation of
   * the authenticator.
   *
   * @return True if the authenticator is expired, false otherwise.
   */
  private def isExpired = expirationDate.isBeforeNow

  /**
   * Checks if the time elapsed since the last time the authenticator was used is longer than
   * the maximum idle timeout specified in the properties.
   *
   * @return True if sliding window expiration is activated and the authenticator is timed out, false otherwise.
   */
  private def isTimedOut = idleTimeout.isDefined && lastUsedDate.plusSeconds(idleTimeout.get).isBeforeNow
}

/**
 * The service that handles the bearer token authenticator.
 *
 * @param settings The authenticator settings.
 * @param dao The DAO to store the authenticator.
 * @param idGenerator The ID generator used to create the authenticator ID.
 * @param clock The clock implementation.
 */
class BearerTokenAuthenticatorService(
  val settings: BearerTokenAuthenticatorSettings,
  dao: AuthenticatorDAO[BearerTokenAuthenticator],
  idGenerator: IDGenerator,
  clock: Clock)
  extends AuthenticatorService[BearerTokenAuthenticator]
  with Logger {

  /**
   * The type of this class.
   */
  type Self = BearerTokenAuthenticatorService

  /**
   * The type of the settings.
   */
  type Settings = BearerTokenAuthenticatorSettings

  /**
   * Gets an authenticator service initialized with a new settings object.
   *
   * @param f A function which gets the settings passed and returns different settings.
   * @return An instance of the authenticator service initialized with new settings.
   */
  def withSettings(f: BearerTokenAuthenticatorSettings => BearerTokenAuthenticatorSettings) = {
    new BearerTokenAuthenticatorService(f(settings), dao, idGenerator, clock)
  }

  /**
   * Creates a new authenticator for the specified login info.
   *
   * @param loginInfo The login info for which the authenticator should be created.
   * @param request The request header.
   * @return An authenticator.
   */
  def create(loginInfo: LoginInfo)(implicit request: RequestHeader): Future[BearerTokenAuthenticator] = {
    idGenerator.generate.map { id =>
      val now = clock.now
      BearerTokenAuthenticator(
        id = id,
        loginInfo = loginInfo,
        lastUsedDate = now,
        expirationDate = now.plusSeconds(settings.authenticatorExpiry),
        idleTimeout = settings.authenticatorIdleTimeout)
    }.recover {
      case e => throw new AuthenticatorCreationException(CreateError.format(ID, loginInfo), e)
    }
  }

  /**
   * Retrieves the authenticator from request.
   *
   * @param request The request header.
   * @return Some authenticator or None if no authenticator could be found in request.
   */
  def retrieve(implicit request: RequestHeader): Future[Option[BearerTokenAuthenticator]] = {
    Future.from(Try(request.headers.get(settings.headerName))).flatMap {
      case Some(token) => dao.find(token)
      case None => Future.successful(None)
    }.recover {
      case e => throw new AuthenticatorRetrievalException(RetrieveError.format(ID), e)
    }
  }

  /**
   * Creates a new bearer token for the given authenticator and return it. The authenticator will also be
   * stored in the backing store.
   *
   * @param authenticator The authenticator instance.
   * @param request The request header.
   * @return The serialized authenticator value.
   */
  def init(authenticator: BearerTokenAuthenticator)(implicit request: RequestHeader): Future[String] = {
    dao.add(authenticator).map { a =>
      a.id
    }.recover {
      case e => throw new AuthenticatorInitializationException(InitError.format(ID, authenticator), e)
    }
  }

  /**
   * Adds a header with the token as value to the result.
   *
   * @param token The token to embed.
   * @param result The result to manipulate.
   * @param request The request header.
   * @return The manipulated result.
   */
  def embed(token: String, result: Result)(implicit request: RequestHeader): Future[AuthenticatorResult] = {
    Future.successful(AuthenticatorResult(result.withHeaders(settings.headerName -> token)))
  }

  /**
   * Adds a header with the token as value to the request.
   *
   * @param token The token to embed.
   * @param request The request header.
   * @return The manipulated request header.
   */
  def embed(token: String, request: RequestHeader): RequestHeader = {
    val additional = Seq(settings.headerName -> token)
    request.copy(headers = request.headers.replace(additional: _*))
  }

  /**
   * @inheritdoc
   *
   * @param authenticator The authenticator to touch.
   * @return The touched authenticator on the left or the untouched authenticator on the right.
   */
  def touch(authenticator: BearerTokenAuthenticator): Either[BearerTokenAuthenticator, BearerTokenAuthenticator] = {
    if (authenticator.idleTimeout.isDefined) {
      Left(authenticator.copy(lastUsedDate = clock.now))
    } else {
      Right(authenticator)
    }
  }

  /**
   * Updates the authenticator with the new last used date in the backing store.
   *
   * We needn't embed the token in the response here because the token itself will not be changed.
   * Only the authenticator in the backing store will be changed.
   *
   * @param authenticator The authenticator to update.
   * @param result The result to manipulate.
   * @param request The request header.
   * @return The original or a manipulated result.
   */
  def update(
    authenticator: BearerTokenAuthenticator,
    result: Result)(implicit request: RequestHeader): Future[AuthenticatorResult] = {

    dao.update(authenticator).map { a =>
      AuthenticatorResult(result)
    }.recover {
      case e => throw new AuthenticatorUpdateException(UpdateError.format(ID, authenticator), e)
    }
  }

  /**
   * Renews an authenticator.
   *
   * After that it isn't possible to use a bearer token which was bound to this authenticator. This
   * method doesn't embed the the authenticator into the result. This must be done manually if needed
   * or use the other renew method otherwise.
   *
   * @param authenticator The authenticator to renew.
   * @param request The request header.
   * @return The serialized expression of the authenticator.
   */
  def renew(authenticator: BearerTokenAuthenticator)(implicit request: RequestHeader): Future[String] = {
    dao.remove(authenticator.id).flatMap { _ =>
      create(authenticator.loginInfo).flatMap(init)
    }.recover {
      case e => throw new AuthenticatorRenewalException(RenewError.format(ID, authenticator), e)
    }
  }

  /**
   * Renews an authenticator and replaces the bearer token header with a new one.
   *
   * The old authenticator will be revoked. After that it isn't possible to use a bearer token which was
   * bound to this authenticator.
   *
   * @param authenticator The authenticator to update.
   * @param result The result to manipulate.
   * @param request The request header.
   * @return The original or a manipulated result.
   */
  def renew(
    authenticator: BearerTokenAuthenticator,
    result: Result)(implicit request: RequestHeader): Future[AuthenticatorResult] = {

    renew(authenticator).flatMap(v => embed(v, result)).recover {
      case e => throw new AuthenticatorRenewalException(RenewError.format(ID, authenticator), e)
    }
  }

  /**
   * Removes the authenticator from cache.
   *
   * @param result The result to manipulate.
   * @param request The request header.
   * @return The manipulated result.
   */
  def discard(
    authenticator: BearerTokenAuthenticator,
    result: Result)(implicit request: RequestHeader): Future[AuthenticatorResult] = {

    dao.remove(authenticator.id).map { _ =>
      AuthenticatorResult(result)
    }.recover {
      case e => throw new AuthenticatorDiscardingException(DiscardError.format(ID, authenticator), e)
    }
  }
}

/**
 * The companion object of the authenticator service.
 */
object BearerTokenAuthenticatorService {

  /**
   * The ID of the authenticator.
   */
  val ID = "bearer-token-authenticator"
}

/**
 * The settings for the bearer token authenticator.
 *
 * @param headerName The name of the header in which the token will be transfered.
 * @param authenticatorIdleTimeout The time in seconds an authenticator can be idle before it timed out. Defaults to 30 minutes.
 * @param authenticatorExpiry The expiry of the authenticator in seconds. Defaults to 12 hours.
 */
case class BearerTokenAuthenticatorSettings(
  headerName: String = "X-Auth-Token",
  authenticatorIdleTimeout: Option[Int] = Some(30 * 60),
  authenticatorExpiry: Int = 12 * 60 * 60)
