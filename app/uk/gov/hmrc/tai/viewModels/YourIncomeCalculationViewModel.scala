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

package uk.gov.hmrc.tai.viewModels

import org.joda.time.LocalDate
import play.api.Play.current
import play.api.i18n.Messages
import uk.gov.hmrc.play.views.helpers.MoneyPounds
import uk.gov.hmrc.tai.model.domain._
import uk.gov.hmrc.tai.model.domain.income._
import uk.gov.hmrc.time.TaxYearResolver
import uk.gov.hmrc.play.language.LanguageUtils.Dates
import uk.gov.hmrc.tai.model.TaxYear

case class PaymentDetailsViewModel(date: LocalDate,
                                   taxableIncome: BigDecimal,
                                   taxAmount: BigDecimal,
                                   nationalInsuranceAmount: BigDecimal)

case class LatestPayment(date: LocalDate,
                         amountYearToDate: BigDecimal,
                         taxAmountYearToDate: BigDecimal,
                         nationalInsuranceAmountYearToDate: BigDecimal,
                         paymentFrequency: PaymentFrequency)

case class YourIncomeCalculationViewModel(
                                              empId: Int,
                                              employerName: String,
                                              payments: Seq[PaymentDetailsViewModel],
                                              employmentStatus: TaxCodeIncomeSourceStatus,
                                              rtiStatus: RealTimeStatus,
                                              latestPayment: Option[LatestPayment],
                                              endDate: Option[LocalDate],
                                              isPension: Boolean,
                                              messageWhenTotalNotEqual: Option[String],
                                              incomeCalculationMessage: String,
                                              incomeCalculationEstimateMessage: Option[String],
                                              hasPayrolledBenefit: Boolean
                                            )

object YourIncomeCalculationViewModel {
  def apply(taxCodeIncome: Option[TaxCodeIncome], employment: Employment)(implicit messages: Messages): YourIncomeCalculationViewModel = {

    val payments = employment.latestAnnualAccount.map(_.payments).getOrElse(Seq.empty[Payment])
    val paymentDetails = payments.map(payment => PaymentDetailsViewModel(
      payment.date, payment.amount, payment.taxAmount, payment.nationalInsuranceAmount))

    val realTimeStatus = employment.latestAnnualAccount.map(_.realTimeStatus).getOrElse(TemporarilyUnavailable)

    val latestPayment = latestPaymentDetails(employment)
    val isPension = taxCodeIncome.exists(_.componentType == PensionIncome)
    val status = taxCodeIncome.map(_.status).getOrElse(Ceased)

    val (incomeCalculationMessage, incomeCalculationEstimateMessage) = taxCodeIncome map { income =>
      incomeExplanationMessage(
        income.status,
        employment,
        isPension,
        income,
        latestPayment.map(_.paymentFrequency),
        latestPayment.map(_.amountYearToDate).getOrElse(BigDecimal(0)),
        latestPayment.map(_.date))
    } getOrElse(None, None)

    YourIncomeCalculationViewModel(
      employment.sequenceNumber,
      employment.name,
      paymentDetails,
      status,
      realTimeStatus,
      latestPayment,
      employment.endDate,
      isPension,
      totalNotEqualMessage(status == Live, paymentDetails, latestPayment, isPension),
      incomeCalculationMessage.getOrElse(""),
      if(status == Ceased) None else incomeCalculationEstimateMessage,
      employment.hasPayrolledBenefit
    )
  }

  private def pensionOrEmpMessage(isPension: Boolean) = if (isPension) "pension" else "emp"

  private def totalNotEqualMessage(isLive: Boolean,
                                   payments: Seq[PaymentDetailsViewModel],
                                   latestPayment: Option[LatestPayment],
                                   isPension: Boolean)(implicit messages: Messages) = {
    val isTotalEqual = payments.map(_.taxAmount).sum == latestPayment.map(_.taxAmountYearToDate).getOrElse(0) &&
      payments.map(_.taxableIncome).sum == latestPayment.map(_.amountYearToDate).getOrElse(0) &&
      payments.map(_.nationalInsuranceAmount).sum == latestPayment.map(_.nationalInsuranceAmountYearToDate).getOrElse(0)

    if(isLive && !isTotalEqual && isPension) {
      Some(messages("tai.income.calculation.totalNotMatching.pension.message"))
    } else if (isLive && !isTotalEqual && !isPension) {
      Some(messages("tai.income.calculation.totalNotMatching.emp.message"))
    } else {
      None
    }

  }

  private def latestPaymentDetails(employment: Employment) = {
    for {
      latestAnnualAccount <- employment.latestAnnualAccount
      latestPayment <- latestAnnualAccount.latestPayment
    } yield LatestPayment(latestPayment.date,
      latestPayment.amountYearToDate,
      latestPayment.taxAmountYearToDate,
      latestPayment.nationalInsuranceAmountYearToDate,
      latestPayment.payFrequency)
  }

  def incomeExplanationMessage(employmentStatus: TaxCodeIncomeSourceStatus,
                               employment: Employment,
                               isPension: Boolean,
                               taxCodeIncome: TaxCodeIncome,
                               paymentFrequency: Option[PaymentFrequency],
                               amountYearToDate: BigDecimal,
                               paymentDate: Option[LocalDate])(implicit messages: Messages): (Option[String], Option[String]) = {

    (getCeasedMsg(employmentStatus, employment, isPension, taxCodeIncome.amount),
      getManualUpdateMsg(taxCodeIncome),
      getSameMsg(employment, taxCodeIncome.amount, amountYearToDate, isPension, paymentDate),
      getPayFreqMsg(employment, isPension, paymentFrequency, amountYearToDate, paymentDate, taxCodeIncome.amount)) match {

      case ((Some(ceasedMsg), Some(ceasedMsgEstimate)), _, _, _) => (Some(ceasedMsg), Some(ceasedMsgEstimate))
      case ((None, Some(ceasedMsgEstimate)), _, _, _) => (None, Some(ceasedMsgEstimate))
      case ((Some(ceasedMsg), None), _, _, _) => (Some(ceasedMsg), None)
      case (_, (Some(manualUpdateMsg), Some(manualUpdateMsgEstimate)), _, _) => (Some(manualUpdateMsg), Some(manualUpdateMsgEstimate))
      case (_, (None, Some(manualUpdateMsgEstimate)), _, _) => (None, Some(manualUpdateMsgEstimate))
      case (_, (Some(manualUpdateMsg), None), _, _) => (Some(manualUpdateMsg), None)
      case (_, _, (Some(sameIncomeMsg), Some(sameIncomeEstimateMsg)), _) => (Some(sameIncomeMsg), Some(sameIncomeEstimateMsg))
      case (_, _, (Some(sameIncomeMsg), None), _) => (Some(sameIncomeMsg), None)
      case (_, _, _, (Some(payFreqMsg), Some(payFreqMsgEstimate))) => (Some(payFreqMsg), Some(payFreqMsgEstimate))
      case (_, _, _, (None, Some(payFreqMsgEstimate))) => (None, Some(payFreqMsgEstimate))
      case (_, _, _, (Some(payFreqMsg), None)) => (Some(payFreqMsg), None)

      case _ => (Some(messages("tai.income.calculation.default." + (if (isPension) "pension" else "emp"),
        Dates.formatDate(TaxYear().end))),
        Some(messages("tai.income.calculation.default.estimate." + (if (isPension) "pension" else "emp"),
          taxCodeIncome.amount)))
    }
  }

  def getCeasedMsg(employmentStatus: TaxCodeIncomeSourceStatus, employment: Employment, isPension: Boolean,
                   amount: BigDecimal)(implicit messages: Messages): (Option[String], Option[String]) = {

    (employmentStatus, employment.endDate.isDefined,
      employment.cessationPay.isDefined) match {

      case (Ceased, true, true) =>
        (Some(messages("tai.income.calculation.rti.ceased." + pensionOrEmpMessage(isPension),
          employment.endDate.map(Dates.formatDate).getOrElse(""))), None)

      case (Ceased, true, false) =>
        (Some(messages("tai.income.calculation.rti.ceased." + pensionOrEmpMessage(isPension) + ".noFinalPay")),
          Some(messages("tai.income.calculation.rti.ceased.noFinalPay.estimate", MoneyPounds(amount, 0).quantity)))

      case (PotentiallyCeased, false, _) =>
        (Some(messages("tai.income.calculation.rti.ceased." + pensionOrEmpMessage(isPension) + ".noFinalPay")),
        Some(messages("tai.income.calculation.rti.ceased.noFinalPay.estimate", MoneyPounds(amount, 0).quantity)))

      case _ => (None, None)
    }
  }

  def getManualUpdateMsg(taxCodeIncome: TaxCodeIncome)(implicit messages: Messages): (Option[String], Option[String]) = {

    (taxCodeIncome.iabdUpdateSource, taxCodeIncome.updateNotificationDate.isDefined, taxCodeIncome.updateActionDate.isDefined) match {
      case (Some(ManualTelephone), true, true) => (Some(messages("tai.income.calculation.manual.update.phone",
        Dates.formatDate(taxCodeIncome.updateActionDate.get), Dates.formatDate(taxCodeIncome.updateNotificationDate.get))),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(ManualTelephone), false, false) | (Some(ManualTelephone), false, true) |
           (Some(ManualTelephone), true, false) => (Some(messages("tai.income.calculation.manual.update.phone.withoutDate")),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(Letter), true, true) => (Some(messages("tai.income.calculation.manual.update.letter",
        Dates.formatDate(taxCodeIncome.updateActionDate.get), Dates.formatDate(taxCodeIncome.updateNotificationDate.get))),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(Letter), false, false) | (Some(Letter), false, true) |
           (Some(Letter), true, false) => (Some(messages("tai.income.calculation.manual.update.letter.withoutDate")),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(Email), true, true) => (Some(messages("tai.income.calculation.manual.update.email",
        Dates.formatDate(taxCodeIncome.updateActionDate.get), Dates.formatDate(taxCodeIncome.updateNotificationDate.get))),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(Email), false, false) | (Some(Email), false, true) |
           (Some(Email), true, false) => (Some(messages("tai.income.calculation.manual.update.email.withoutDate")),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(AgentContact), _, _) => (Some(messages("tai.income.calculation.agent")),
        Some(messages("tai.income.calculation.agent.estimate", taxCodeIncome.amount)))

      case (Some(OtherForm) | Some(InformationLetter), true, true) =>
        (Some(messages("tai.income.calculation.manual.update.informationLetter", Dates.formatDate(taxCodeIncome.updateActionDate.get),
          Dates.formatDate(taxCodeIncome.updateNotificationDate.get))),
          Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(OtherForm) | Some(InformationLetter), false, false)
           | (Some(OtherForm) | Some(InformationLetter), false, true)
           | (Some(OtherForm) | Some(InformationLetter), true, false) =>
        (Some(messages("tai.income.calculation.manual.update.informationLetter.withoutDate")),
          Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(Internet), true, _) => (Some(messages("tai.income.calculation.manual.update.internet",
        Dates.formatDate(taxCodeIncome.updateNotificationDate.get))),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case (Some(Internet), false, _) => (Some(messages("tai.income.calculation.manual.update.internet.withoutDate")),
        Some(messages("tai.income.calculation.rti.manual.update.estimate", taxCodeIncome.amount)))

      case _ => (None, None)
    }
  }

  def getPayFreqMsg(employment: Employment, isPension: Boolean, paymentFrequency: Option[PaymentFrequency],
                    amountYearToDate: BigDecimal, paymentDate: Option[LocalDate],
                    amount: BigDecimal)(implicit messages: Messages): (Option[String], Option[String]) = {

    val isMidYear = employment.startDate.isAfter(TaxYear().start)
    val pensionOrEmp = pensionOrEmpMessage(isPension)

    (paymentFrequency, isMidYear) match {
      case ((Some(Weekly) | Some(FortNightly) | Some(FourWeekly) | Some(Monthly) | Some(Quarterly) | Some(BiAnnually)), false) =>
        (Some(messages("tai.income.calculation.rti.continuous.weekly." + pensionOrEmp,
          MoneyPounds(amountYearToDate, 2).quantity, paymentDate.map(Dates.formatDate).getOrElse(""))),
          Some(messages("tai.income.calculation.rti." + pensionOrEmp + ".estimate",
            MoneyPounds(amount, 0).quantity)))
      case (Some(Annually), false) =>
        (Some(messages("tai.income.calculation.rti.continuous.annually." + pensionOrEmp,
          MoneyPounds(amountYearToDate, 2).quantity)),
          Some(messages("tai.income.calculation.rti." + pensionOrEmp + ".estimate",
            MoneyPounds(amount, 0).quantity)))
      case (Some(OneOff), false) =>
        (Some(messages("tai.income.calculation.rti.oneOff." + pensionOrEmp,
          MoneyPounds(amountYearToDate, 2).quantity)),
          Some(messages("tai.income.calculation.rti." + pensionOrEmp + ".estimate",
            MoneyPounds(amount, 0).quantity)))
      case (Some(Irregular), false) =>
        val estPay = amount
        (None, Some(messages("tai.income.calculation.rti.irregular." + pensionOrEmp, MoneyPounds(estPay, 0).quantity)))
      case ((Some(Weekly) | Some(FortNightly) | Some(FourWeekly) | Some(Monthly) | Some(Quarterly) | Some(BiAnnually) | Some(Annually)), true) =>
        (Some(messages("tai.income.calculation.rti.midYear.weekly", Dates.formatDate(employment.startDate),
          paymentDate.map(Dates.formatDate).getOrElse(""), MoneyPounds(amountYearToDate, 2).quantity)),
          Some(messages("tai.income.calculation.rti." + pensionOrEmp + ".estimate",
            MoneyPounds(amount, 0).quantity)))
      case (Some(OneOff), true) =>
        (Some(messages("tai.income.calculation.rti.oneOff." + pensionOrEmp, MoneyPounds(amountYearToDate, 2).quantity)),
          Some(messages("tai.income.calculation.rti." + pensionOrEmp + ".estimate",
            MoneyPounds(amount, 0).quantity)))
      case (Some(Irregular), true) =>
        val estPay =  amount
        (None, Some(messages("tai.income.calculation.rti.irregular." + pensionOrEmp, MoneyPounds(estPay, 0).quantity)))
      case _ => (None, None)
    }
  }

  def getSameMsg(employment: Employment, amount: BigDecimal, amountYearToDate: BigDecimal,
                 isPension: Boolean, paymentDate: Option[LocalDate])(implicit messages: Messages): (Option[String], Option[String]) = {

    val startDate = if (TaxYearResolver.fallsInThisTaxYear(employment.startDate)) employment.startDate else TaxYear().start

    if (amount == amountYearToDate) {
      (Some(messages("tai.income.calculation.rti." + pensionOrEmpMessage(isPension) +
        ".same", Dates.formatDate(startDate),
        paymentDate.map(Dates.formatDate).getOrElse(""), MoneyPounds(amountYearToDate, 0).quantity)), None)
    } else { (None, None) }
  }
}