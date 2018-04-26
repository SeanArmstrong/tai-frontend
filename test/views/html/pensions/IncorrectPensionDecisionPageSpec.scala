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

package views.html.pensions

import play.api.i18n.Messages
import play.twirl.api.Html
import uk.gov.hmrc.tai.forms.pensions.UpdateRemovePensionForm
import uk.gov.hmrc.tai.util.FormValuesConstants
import uk.gov.hmrc.tai.util.viewHelpers.TaiViewSpec
import uk.gov.hmrc.tai.viewModels.pensions.PensionProviderViewModel

class IncorrectPensionDecisionPageSpec extends TaiViewSpec with FormValuesConstants {

  "IncorrectPension page" must {
    behave like pageWithTitle(messages("tai.incorrectPension.decision.title"))
    behave like pageWithBackLink
    behave like pageWithCombinedHeader(messages("tai.incorrectPension.decision.title"),
      messages("tai.incorrectPension.decision.main.heading", model.pensionName))

    behave like pageWithYesNoRadioButton(
      UpdateRemovePensionForm.IncorrectPensionDecision+"-yes",
      UpdateRemovePensionForm.IncorrectPensionDecision+"-no",
      messages("tai.pension.decision.radio1"),
      messages("tai.pension.decision.radio2"))

    behave like pageWithContinueButtonForm(controllers.pensions.routes.IncorrectPensionProviderController.handleDecision().url)
    behave like pageWithCancelLink(controllers.routes.IncomeSourceSummaryController.onPageLoad(model.id))

    "show error" when {
      "form contains error" in {
        val pensionUpdateRemoveFormWithError = UpdateRemovePensionForm.form.bind(
          Map(UpdateRemovePensionForm.IncorrectPensionDecision -> ""))
        val viewWithError: Html = views.html.pensions.incorrectPensionDecision(model, pensionUpdateRemoveFormWithError)

        val errorDoc = doc(viewWithError)

        errorDoc must haveElementAtPathWithText(".error-message", Messages("tai.error.chooseOneOption"))
        errorDoc must haveElementAtPathWithClass("form div", "form-group-error")
      }
    }
  }


  private lazy val pensionUpdateRemoveForm = UpdateRemovePensionForm.form.bind(
    Map(UpdateRemovePensionForm.IncorrectPensionDecision -> YesValue))
  private lazy val model = PensionProviderViewModel(1, "Test Pension")
  override def view: Html = views.html.pensions.incorrectPensionDecision(model, pensionUpdateRemoveForm)
}
