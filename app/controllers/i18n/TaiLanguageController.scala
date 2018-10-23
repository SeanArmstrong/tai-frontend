/*
 * Copyright 2018 HM Revenue & Customs
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

package controllers.i18n

import controllers.auth.WithAuthorisedForTaiLite
import controllers.{AuthenticationConnectors, ServiceCheckLite, TaiBaseController}
import play.api.Mode.Mode
import play.api.{Configuration, Play}
import play.api.i18n.Lang
import play.api.mvc.{Action, AnyContent}
import uk.gov.hmrc.play.frontend.auth.DelegationAwareActions
import uk.gov.hmrc.play.language.{LanguageController, LanguageUtils}
import uk.gov.hmrc.play.partials.FormPartialRetriever
import uk.gov.hmrc.renderer.TemplateRenderer
import uk.gov.hmrc.tai.config.{FeatureTogglesConfig, TaiHtmlPartialRetriever}
import uk.gov.hmrc.tai.connectors.LocalTemplateRenderer
import uk.gov.hmrc.tai.service.PersonService
import play.api.Play.current
import play.api.i18n.Messages
import play.api.i18n.Messages.Implicits._

import scala.concurrent.Future

trait TaiLanguageController extends LanguageController with TaiBaseController
  with DelegationAwareActions
  with WithAuthorisedForTaiLite
  with FeatureTogglesConfig {

  def personService: PersonService

  override protected def languageMap: Map[String, Lang] = Map(
    "english" -> Lang("en"),
    "cymraeg" -> Lang("cy")
  )

  override protected def fallbackURL: String = controllers.routes.WhatDoYouWantToDoController.whatDoYouWantToDoPage.url

  protected def isWelshEnabled = welshLanguageEnabled

  override def switchToLanguage (language: String): Action[AnyContent] = authorisedForTai(personService).async {
    implicit user =>
      implicit person =>
        implicit request =>
          ServiceCheckLite.personDetailsCheck {

            val newLanguage =
              if (isWelshEnabled)
                languageMap.getOrElse(language, LanguageUtils.getCurrentLang)
              else
                LanguageUtils.getCurrentLang

            val redirectURL = request.headers.get(REFERER).getOrElse(fallbackURL)

            Future.successful(
              Redirect(redirectURL).withLang(Lang(newLanguage.code))
            )
          }
  }
}
// $COVERAGE-OFF$
object TaiLanguageController extends TaiLanguageController with AuthenticationConnectors {

  override def personService: PersonService = PersonService
  override implicit def templateRenderer: TemplateRenderer = LocalTemplateRenderer
  override implicit def partialRetriever: FormPartialRetriever = TaiHtmlPartialRetriever
  override protected val mode: Mode = Play.current.mode

  override protected val runModeConfiguration: Configuration = Play.current.configuration
}
// $COVERAGE-ON$

