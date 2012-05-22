/*
 * Surveys.scala
 * 
 * Copyright (c) 2012, Aishwarya Singhal. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 */
package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._

import models._
import models.Languages._
import java.security.SecureRandom
import java.math.BigInteger
import java.util.Date

/**
 * A Controller  for managing survey related operations.
 *
 * @author Aishwarya Singhal
 */
object Surveys extends Controller with Secured {
  import dao.Mongo._
  import helpers.SurveyHelper._
  import helpers.FilesHelper._

  /**
   * The survey form data as expected from the front end.
   */
  val surveyForm = Form(
    tuple(
      "surveyname" -> text,
      "language" -> text,
      "introText" -> text,
      "thankyouText" -> text,
      "accessType" -> text,
      "bodycolor" -> text,
      "containercolor" -> text,
      "textColor" -> text,
      "logoBgColor" -> text,
      "includeProgress" -> text,
      "logoAlignment" -> text
    )
  )
   
  /**
   * Shows all surveys for the current user.
   */
   def dashboard = IsAuthenticated { user => _ =>
   	var surveys = Survey.find("owner" -> user).map { x => 
       val m = x.toMap
       // remove the keys we won't use and save on deserialization effort
       m.remove("questions")
       m.remove("layout")
       deserialize(classOf[Survey], m) 
     }.toList.sortWith(_.history.updated_at after _.history.updated_at)
      Ok(views.html.users.dashboard(surveys, user))
   }

  /**
   * Opens up a page that allows survey creation.
   */
   def newsurvey = IsAuthenticated { user => _ =>
      Ok(views.html.surveys.newsurvey(user))
   }

  /**
   * Load a survey so it can be editted.
   *
   * @param survey id
   */
   def editinfo(id: String) = IsAuthenticated { user => _ =>
      val survey = Survey.findOne("surveyId" -> id, "owner" -> user).map { s => 
        val m = s.toMap
       // remove the keys we won't use and save on deserialization effort
        m.remove("questions")
        deserialize(classOf[Survey], m)
      }
      Ok(views.html.surveys.edit(user, survey.getOrElse(null)))
   }

  /**
   * Creates a new survey
   *
   * @param survey id
   */
   def create = IsMultipartAuthenticated(parse.multipartFormData) { user => implicit request => 
     val (surveyname, language, introText, thankyouText, accessType, bodycolor, containercolor, textColor, 
       logoBgColor, includeProgress, logoAlignment) = surveyForm.bindFromRequest.get
    val id = Survey.nextId
    val random = new SecureRandom
    val hash_string = new BigInteger(80, random).toString(32)
    var logoFile: String = null

    request.body.file("logo").map { logo => logoFile = uploadFile(hash_string, logo) }

    val history = new History(new Date, user, new Date, user)
    val layout = new SurveyLayout(logoAlignment, includeProgress.toBoolean, bodycolor, containercolor, textColor, logoBgColor)
    new Survey(id, surveyname, language, List(user), hash_string, null, history, introText, thankyouText, logoFile, accessType, layout).save

    Redirect(routes.Surveys.edit(id))
   }

  /**
   * Updates a survey information.
   *
   * @param survey id
   */
   def updateinfo(id: String) = IsMultipartAuthenticated(parse.multipartFormData) { user => implicit request => 
     val (surveyname, language, introText, thankyouText, accessType, bodycolor, containercolor, textColor, 
       logoBgColor, includeProgress, logoAlignment) = surveyForm.bindFromRequest.get

      Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
        val layout = new SurveyLayout(logoAlignment, includeProgress.toBoolean, bodycolor, containercolor, textColor, logoBgColor)
        // Update history
        var history = deserialize(classOf[History], s.get("history").asInstanceOf[com.mongodb.BasicDBObject].toMap)
        history = new History(history.created_at, history.created_by, new Date, user)
        Survey.update(s.get("_id"), "name" -> surveyname, "language" -> language, "intro_text" -> introText, "thank_you_text" -> thankyouText, 
          "layout" -> layout, "accessType" -> accessType, "history" -> history)

        var logoFile: String = null
        request.body.file("logo").map { logo => 
          // first delete existing logo
          val existing_hash = deleteExistingLogo(s.toMap)
          logoFile = uploadFile(s.get("hash_string").toString, logo, existing_hash) 
          Survey.update(s.get("_id"), "logo" -> logoFile)
        }
     }

    Redirect(routes.Surveys.edit(id))
   }

  /**
   * Load a questionnaire so it can be editted.
   *
   * @param survey id
   */
   def edit(id: String) = IsAuthenticated { user => _ => 
      var survey: Survey = null
      var q = 0
      var pageIds = List[String]()
      Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
  	    survey = deserialize(classOf[Survey], s.toMap)
  	    if (survey.questions != null && !survey.questions.isEmpty) { 
		    import com.mongodb._
  	    	s.toMap.get("questions").asInstanceOf[BasicDBList].toArray.foreach { case m: BasicDBObject => 
              val data = m.toMap
              val questionId = data.get("questionId").asInstanceOf[String]
    	    		val i = questionId.substring(1).toInt 
    	    		if (i > q) q = i
              data.get("qType").asInstanceOf[String] match {
                case "page"=> pageIds ::= questionId
                case _ =>
              }
  	        }
  	    }
      }
      Ok(views.html.surveys.questions(id, user, q, survey, pageIds.reverse))
   }

  /**
   * Updates the questionnaire and/ or the status of the survey.
   *
   * @param survey id
   */
   def update(id: String) = IsAuthenticated { user => implicit request => 
   	var questions: Seq[Question] = null

    var status = ""

    var accessType = "open"

   	Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
       val language = s.get("language").toString
       accessType = s.get("accessType").toString

       getRequestData().foreach { params =>
        questions = getQuestions()(params, language)
        val statuses = params("survey_status").asInstanceOf[Seq[String]]
        status = if (statuses.isEmpty) "" else statuses(0)
       }

      // Update history
      var history = deserialize(classOf[History], s.get("history").asInstanceOf[com.mongodb.BasicDBObject].toMap)
      history = new History(history.created_at, history.created_by, new Date, user)

   		Survey.update(s.get("_id"), "questions" -> questions, "history" -> history)
      if (status != "") {
        Survey.update(s.get("_id"), "status" -> status)
      }
   	}

     if (status == "Live" && accessType != "open") {
      Redirect(routes.Participants.invite(id))
     } else {
      Redirect(routes.Surveys.dashboard)
     }
   }

  /**
   * Updates the survey status to 'Closed' so that it can no longer be accessed by survey participants.
   * This allows for a freeze on response capturing.
   *
   * @param survey id
   */
   def close(id: String) = IsAuthenticated { user => implicit request => 
     Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
      // Update history
      var history = deserialize(classOf[History], s.get("history").asInstanceOf[com.mongodb.BasicDBObject].toMap)
      history = new History(history.created_at, history.created_by, new Date, user)

      Survey.update(s.get("_id"), "status" -> "Closed", "history" -> history)
     }

      Redirect(routes.Surveys.dashboard)
   }

  /**
   * Delete a survey and all responses associated. Also delete any uploaded files for this survey.
   *
   * @param survey id
   */
   def delete(id: String) = IsAuthenticated { user => implicit request => 
    deleteSurvey(id, user, false)

    Redirect(routes.Surveys.dashboard)
   }

  /**
   * Delete all responses associated with a survey
   *
   * @param survey id
   */
   def flush(id: String) = IsAuthenticated { user => implicit request => 
    deleteSurvey(id, user, true)

    Redirect(routes.Surveys.dashboard)
   }

   def template(id: String, tid: String) = IsAuthenticated { user => implicit request => 
     Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
       Template.findOne("_id" -> new org.bson.types.ObjectId(tid)).foreach { t => 
         val template = deserialize(classOf[Template], t.toMap)
         Survey.update(s.get("_id"), "questions" -> template.questions)
       }
     }
    Redirect(routes.Surveys.edit(id))
   }

  /**
   * Generates a preview of the survey. The survey questions are rendered exactly as they would on a 
   * live survey but the responses are not processed.
   *
   * @param survey id
   */
   def preview(id: String) = IsAuthenticated { user => implicit request => 
      var page = 1

      getRequestData().foreach { params =>
        page = params("pageNum").asInstanceOf[Seq[String]](0).toInt + 1
      }

      var questions = List[Question]()
      var survey: (Survey, Int) = (null, 0)

      Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
        survey = findQuestionsForPage(id, page, s.toMap) { q => questions ::= q }
      }

      val s = survey._1
      Ok(views.html.respondents.preview(s, questions.reverse, page, null, survey._2))
   }

  /**
   * Allows for editting of the flow of a survey.
   *
   * @param survey id
   */
   def flow(id: String) = IsAuthenticated { user => implicit request => 

     var pages = List[Question]()

      Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
        val survey = deserialize(classOf[Survey], s.toMap)
        val breaks = survey.questions.filter(q => q.qType == "page")
        pages :::= breaks.toList
      }
      Ok(views.html.surveys.flow(user, id, pages))
   }

   def questions(id: String, qId: String) = IsAuthenticated { user => implicit request => 
     import play.api.libs.json.Json._
    var questions = List[Question]()
     Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
        val survey = deserialize(classOf[Survey], s.toMap)
        var found = false
        survey.questions.map(q => q match {
          case p: PageBreak => if (p.questionId == qId) { found = true }
          case p: PlainText => 
          case _ => if (!found) questions ::= q
          })
      }
     Ok//(toJson(questions.reverse.toList))
   }

  /**
   * Allows for editting of the owners of a survey.
   *
   * @param survey id
   */
   def collaborate(id: String) = IsAuthenticated { user => implicit request => 
     var pages = List[Question]()
     var owners = List[String]()

      Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
        getRequestData().foreach { params => 
          owners = params("owners").asInstanceOf[List[String]]
          Survey.update(s.get("_id"), "owner" -> owners)
        }

        if (owners.isEmpty) {
          val survey = deserialize(classOf[Survey], s.toMap)
          owners = survey.owner
        }
      }
      Ok(views.html.surveys.collaborate(id, owners, user))
   }

  /**
   * Delete a survey and/ or all responses associated. Also delete any uploaded files for this survey.
   */
   private def deleteSurvey(id: String, user: String, dataOnly: Boolean = false) = {
    Survey.findOne("surveyId" -> id, "owner" -> user).foreach { s => 
      SurveyResponse.deleteAll("surveyId" -> id)
      Participant.deleteAll("surveyId" -> id)
      if (!dataOnly) {
        val hash_string = s.get("hash_string").toString
        val path = "./public/uploads/" + hash_string + "/"
        deleteDirectory(new java.io.File(path))
        Survey.delete(s.get("_id"))
      }
    }
   }
}