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

package uk.gov.hmrc.tai.connectors

import controllers.FakeTaiPlayApplication
import org.mockito.Matchers
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.Mode.Mode
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class SessionConnectorSpec extends PlaySpec with MockitoSugar with FakeTaiPlayApplication {

  "Session Connector" must {
    "return Http response" when {
      "cache is invalidated" in {
        val sut = createSUT
        val result = Await.result(sut.invalidateCache(), 5.seconds)

        result.status mustBe 200
      }
    }

    "call the proper url to invalidate the cache" in {
      val sut = createSUT
      Await.result(sut.invalidateCache(), 5.seconds)
      verify(sut.httpHandler, times(1)).deleteFromApi(Matchers.eq("localhost/tai/session-cache"))(any(), any())
    }
  }

  private implicit val hc = HeaderCarrier()

  private def createSUT = new SUT

  class SUT extends SessionConnector {
    override val httpHandler: HttpHandler = mock[HttpHandler]
    override lazy val baseURL: String = "localhost"
    when(httpHandler.deleteFromApi(any())(any(), any())).thenReturn(Future.successful(HttpResponse(200)))

    override protected val mode: Mode = app.mode

    override protected val runModeConfiguration: Configuration = app.configuration
  }

}
