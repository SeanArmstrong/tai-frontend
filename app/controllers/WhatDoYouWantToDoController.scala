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

package controllers

import com.google.inject.{Inject, Singleton}
import controllers.audit.Auditable
import controllers.auth.{TaiUser, WithAuthorisedForTaiLite}
import play.api.Logger
import play.api.Play.current
import play.api.i18n.Messages.Implicits._
import play.api.mvc._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.frontend.auth.DelegationAwareActions
import uk.gov.hmrc.play.partials.FormPartialRetriever
import uk.gov.hmrc.tai.config.{FeatureTogglesConfig, TaiHtmlPartialRetriever}
import uk.gov.hmrc.tai.connectors.LocalTemplateRenderer
import uk.gov.hmrc.tai.connectors.responses.{TaiResponse, TaiSuccessResponseWithPayload}
import uk.gov.hmrc.tai.forms.WhatDoYouWantToDoForm
import uk.gov.hmrc.tai.model.TaxYear
import uk.gov.hmrc.tai.model.domain.{Employment, HasTaxCodeChanged, TaxCodeMismatch}
import uk.gov.hmrc.tai.model.domain.income.TaxCodeIncome
import uk.gov.hmrc.tai.service._
import uk.gov.hmrc.tai.viewModels.WhatDoYouWantToDoViewModel
import uk.gov.hmrc.time.TaxYearResolver

import scala.concurrent.Future

@Singleton
class WhatDoYouWantToDoController @Inject()(personService: PersonService,
                                            employmentService: EmploymentService,
                                            taxAccountService: TaxAccountService,
                                            taxCodeChangeService: TaxCodeChangeService,
                                            trackingService: TrackingService,
                                            auditService: AuditService)
  extends TaiBaseController
    with AuthenticationConnectors
    with DelegationAwareActions
    with WithAuthorisedForTaiLite
    with Auditable
    with FeatureTogglesConfig {

  override implicit def templateRenderer = LocalTemplateRenderer
  override implicit def partialRetriever: FormPartialRetriever = TaiHtmlPartialRetriever

  implicit val recoveryLocation:RecoveryLocation = classOf[WhatDoYouWantToDoController]

  def whatDoYouWantToDoPage(): Action[AnyContent] = authorisedForTai(personService).async {
    implicit user =>
      implicit person =>
        implicit request =>
          ServiceCheckLite.personDetailsCheck {
            val possibleRedirectFuture =
              for {
                taxAccountSummary <- taxAccountService.taxAccountSummary(Nino(user.getNino), TaxYear())
                _ <- employmentService.employments(Nino(user.getNino), TaxYear())
                prevYearEmployments <- previousYearEmployments(Nino(user.getNino))
              } yield {
                val npsFailureHandlingPf: PartialFunction[TaiResponse, Option[Result]] =
                  npsTaxAccountAbsentResult_withEmployCheck(prevYearEmployments) orElse
                  npsTaxAccountCYAbsentResult_withEmployCheck(prevYearEmployments) orElse
                  npsNoEmploymentForCYResult_withEmployCheck(prevYearEmployments) orElse
                  npsNoEmploymentResult orElse
                  npsTaxAccountDeceasedResult orElse
                  {case _=> None}

                npsFailureHandlingPf(taxAccountSummary)
              }

            possibleRedirectFuture.flatMap(
              _.map(Future.successful).getOrElse( allowWhatDoYouWantToDo )
            )

          } recoverWith (hodBadRequestResult orElse hodInternalErrorResult)
  }

  def handleWhatDoYouWantToDoPage(): Action[AnyContent] = authorisedForTai(personService).async {
    implicit user =>
      implicit person =>
        implicit request =>

          WhatDoYouWantToDoForm.createForm.bindFromRequest.fold(
            formWithErrors => {
              trackingService.isAnyIFormInProgress(user.getNino) flatMap { trackingResponse =>
                if(cyPlusOneEnabled){
                    taxAccountService.taxAccountSummary(Nino(user.getNino), TaxYear().next) map {
                      case TaiSuccessResponseWithPayload(_) =>
                        BadRequest(views.html.whatDoYouWantToDo(formWithErrors, WhatDoYouWantToDoViewModel(trackingResponse, cyPlusOneEnabled)))
                      case _ =>
                        BadRequest(views.html.whatDoYouWantToDo(formWithErrors, WhatDoYouWantToDoViewModel(trackingResponse, isCyPlusOneEnabled = false)))
                    }
                } else {
                  Future.successful(BadRequest(views.html.whatDoYouWantToDo(formWithErrors, WhatDoYouWantToDoViewModel(trackingResponse, cyPlusOneEnabled))))
                }
              }
            },
            formData => {
              formData.whatDoYouWantToDo match {
                case Some("currentTaxYear") => Future.successful(Redirect(routes.TaxAccountSummaryController.onPageLoad()))
                case Some("lastTaxYear") => Future.successful(Redirect(routes.PayeControllerHistoric.payePage(TaxYear(TaxYearResolver.currentTaxYear-1))))
                case Some("nextTaxYear") => Future.successful(Redirect(routes.IncomeTaxComparisonController.onPageLoad()))
              }
            }
          )
  }

  private def allowWhatDoYouWantToDo(implicit request: Request[AnyContent], user: TaiUser): Future[Result] = {

    val nino = Nino(user.getNino)
    val currentTaxYearEmployments = employmentService.employments(nino, TaxYear())
    val currentTaxYearTaxCodes = taxAccountService.taxCodeIncomes(nino, TaxYear())
    (for {
      employments <- currentTaxYearEmployments
      taxCodes <- currentTaxYearTaxCodes
    } yield {
      val noOfTaxCodes: Seq[TaxCodeIncome] = taxCodes match {
        case TaiSuccessResponseWithPayload(taxCodeIncomes: Seq[TaxCodeIncome]) => taxCodeIncomes
        case _ => Seq.empty[TaxCodeIncome]
      }
      auditService.sendUserEntryAuditEvent(nino, request.headers.get("Referer").getOrElse("NA"), employments, noOfTaxCodes)
    }).recover{
      auditError
    }

    trackingService.isAnyIFormInProgress(user.getNino) flatMap { trackingResponse =>

      (cyPlusOneEnabled, tileViewEnabled) match {
        case (true, true) => {
          val hasTaxCodeChanged: Future[HasTaxCodeChanged] = taxCodeChangeService.hasTaxCodeChanged(Nino(user.getNino))
          val cy1TaxAccountSummary: Future[TaiResponse] = taxAccountService.taxAccountSummary(Nino(user.getNino), TaxYear().next)

          for {
            taxCodeChanged <- hasTaxCodeChanged
            taxAccountSummary <- cy1TaxAccountSummary
          } yield {
            Logger.debug("[WhatDoYouWantToDoController] TaxCodeChanged: " + taxCodeChanged)
            taxAccountSummary match {
              case TaiSuccessResponseWithPayload(_) =>
                Ok(views.html.whatDoYouWantToDoTileView(WhatDoYouWantToDoForm.createForm, WhatDoYouWantToDoViewModel(trackingResponse, cyPlusOneEnabled, taxCodeChanged.changed, taxCodeChanged.mismatch)))
              case _ =>
                Ok(views.html.whatDoYouWantToDoTileView(WhatDoYouWantToDoForm.createForm, WhatDoYouWantToDoViewModel(trackingResponse, isCyPlusOneEnabled = false)))
            }
          }
        }
        case (true, false) => taxAccountService.taxAccountSummary(Nino(user.getNino), TaxYear().next) map {
          case TaiSuccessResponseWithPayload(_) =>
            Ok(views.html.whatDoYouWantToDo(WhatDoYouWantToDoForm.createForm, WhatDoYouWantToDoViewModel(trackingResponse, cyPlusOneEnabled)))
          case _ =>
            Ok(views.html.whatDoYouWantToDo(WhatDoYouWantToDoForm.createForm, WhatDoYouWantToDoViewModel(trackingResponse, isCyPlusOneEnabled = false)))
        }
        case (false, true) =>
          taxCodeChangeService.hasTaxCodeChanged(Nino(user.getNino)).map (hasTaxCodeChanged =>
            Ok(views.html.whatDoYouWantToDoTileView(WhatDoYouWantToDoForm.createForm, WhatDoYouWantToDoViewModel(trackingResponse, cyPlusOneEnabled, hasTaxCodeChanged.changed, hasTaxCodeChanged.mismatch)))
          )
        case (false, false) =>
          Future.successful(Ok(views.html.whatDoYouWantToDo(WhatDoYouWantToDoForm.createForm, WhatDoYouWantToDoViewModel(trackingResponse, cyPlusOneEnabled))))
      }
    }
  }

 private def auditError(implicit request: Request[AnyContent], user: TaiUser): PartialFunction[Throwable, Unit] = {
    case e =>
      Logger.warn(s"<Send audit event failed to get either taxCodeIncomes or employments for nino ${user.getNino}  with exception: ${e.getClass()}", e)
  }

  private[controllers] def previousYearEmployments(nino: Nino)(implicit hc: HeaderCarrier): Future[Seq[Employment]] = {
    employmentService.employments(nino, TaxYear(TaxYearResolver.currentTaxYear-1)) recover {
      case _ => Nil
    }
  }
}
