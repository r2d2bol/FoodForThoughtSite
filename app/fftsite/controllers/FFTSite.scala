package fftsite.controllers

import fftsite._

import java.io._
import org.joda.time._
import fftsite.models._

import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.templates.Html
import play.api.Play
import java.io.File
import play.api.data._
import play.api.data.Forms._
import play.api.db._
import play.api.Play.current
import scala.slick.jdbc.meta.MTable
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import securesocial.core._

import play.api.data.validation.Constraints

import scala.pickling._
import scala.pickling.binary._

import st.sparse.sundry._

import st.sparse.persistentmap._
import st.sparse.persistentmap.CustomPicklers._
import scala.math.round
import scala.util.control.Breaks._

object FFTSite extends Controller with securesocial.core.SecureSocial {
  val updateSaved = "fftUpdateSaved"

  def loadResourceAsString(resource: String): String = {
    val file = new File(new File(new File(Play.application.path.getPath), "/public/"), resource)

    //    val file = getClass.getResource(resource).getFile
    scala.io.Source.fromFile(file).mkString
  }

  def loadConfigAsString(resource: String): String = {
    val file = new File(new File(new File(Play.application.path.getPath), "/conf/"), resource)

    //    val file = getClass.getResource(resource).getFile
    scala.io.Source.fromFile(file).mkString
  }

  def getQuarterDates:Map[YearAndQuarter,QuarterDates] = {
    val quartersStr =loadConfigAsString("quarters.conf");
    val lines = quartersStr.split("\n")
    var quarterDates = Map[YearAndQuarter,QuarterDates]()
    for(line <- lines) {
      val vals = line.split(",")
      val quarter = YearAndQuarter(vals(0).toInt,
        vals(1) match { case "winter" => 0; case "spring" => 1; case "summer" => 2; case "fall" => 3})
      quarterDates.+=(quarter -> QuarterDates(new LocalDate(vals(2)), new LocalDate(vals(3))))

    }
    return quarterDates
  }

  def dateToday = {
    val timeZone = DateTimeZone.forID("America/Los_Angeles")
    new LocalDate(timeZone)
  }

  def markdownToHTML(markdown: String) =
    Html(new org.pegdown.PegDownProcessor().markdownToHtml(markdown))

  def resourceMarkdownToHTML = (markdownToHTML _) compose loadResourceAsString

  def getIndex = Action { implicit request => Ok(views.html.index()) }

  def getMethods = Action { implicit request => Ok(views.html.methods()) }

  def getResults = Action { implicit request => Ok(views.html.results()) }

  def getFAQ = Action { implicit request => Ok(views.html.faq()) }

  def getBlog = Action { implicit request => Redirect("http://so3fft.blogspot.com") }

  def getGroup = Action { implicit request =>
    Redirect("https://groups.google.com/forum/?fromgroups#!forum/so3foodforthought")
  }

  def getSource = Action { implicit request =>
    Redirect("https://github.com/emchristiansen/FoodForThought")
  }

  def getAuthenticateDropdown = UserAwareAction { implicit request =>
    request.user match {
      case Some(user) => Ok(views.html.accountAuthenticated(user.firstName))
      case None => Ok(views.html.accountNotAuthenticated())
    }
  }

  // TODO: Delete this.
  //  def testID = Models.users(IdentityId("echristiansen@eng.ucsd.edu", "userpass"))
  //  def testID = Models.users(IdentityId("echristiansen@cs.ucsd.edu", "userpass"))

  val profileForm = Form(tuple(
    "userInformationPart" -> mapping(
      "studentID" -> optional(text),
      "employeeID" -> optional(text))(UserInformation.apply)(UserInformation.unapply),
    "employmentQuarter" -> optional(text),
    "employmentStatus" -> optional(text),
    "dietaryInformation" -> mapping(
      "restrictions" -> optional(text),
      "preferences" -> optional(text),
      "additionalNotes" -> optional(text))(DietaryInformation.apply)(DietaryInformation.unapply),
    "consent" -> checked("You've gotta check this.")))

  def employmentHistory(identityId: IdentityId) =
    Models.employmentHistory.getOrElse(
      identityId,
      EmploymentHistory(Map()))

  // TODO: Change to SecuredAction
  def getProfile = SecuredAction { implicit request =>
    val user: SocialUser = request.user.asInstanceOf[SocialUser]

    val userInformation = Models.userInformation.getOrElse(
      user.identityId,
      UserInformation(None, None))

    val dietaryInformation = Models.dietaryInformation.getOrElse(
      user.identityId,
      DietaryInformation(None, None, None))

    Ok(views.html.profile(
      employmentHistory(user.identityId),
      profileForm.fill((
        userInformation,
        None,
        None,
        dietaryInformation,
        false))))
  }

  // TODO: Add flashing.
  def postProfile = SecuredAction { implicit request =>
    val user: SocialUser = request.user.asInstanceOf[SocialUser]

    profileForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(views.html.profile(
          employmentHistory(user.identityId),
          formWithErrors)),
      value => {
        Models.userInformation(user.identityId) = value._1
        Models.dietaryInformation(user.identityId) = value._4

        for (
          employmentQuarterString <- value._2;
          employmentStatusString <- value._3
        ) {
          val employmentQuarter = {
            val List(year, quarter) = employmentQuarterString.split("-").toList
            YearAndQuarter(year.toInt, quarter.toInt)
          }
          val employmentStatus = EmploymentStatus(employmentStatusString)

          val current = employmentHistory(user.identityId)
          Models.employmentHistory(user.identityId) = EmploymentHistory(
            current.history + (employmentQuarter -> employmentStatus))
        }

        Redirect(fftsite.controllers.routes.FFTSite.getProfile)
      })
  }

  def getDeleteEmploymentHistory(yearAndQuarter: String) =
    SecuredAction { implicit request =>
      val user: SocialUser = request.user.asInstanceOf[SocialUser]

      val current = employmentHistory(user.identityId)
      Models.employmentHistory(user.identityId) = EmploymentHistory(
        current.history.filterKeys(_.toString != yearAndQuarter))

      Redirect(fftsite.controllers.routes.FFTSite.getProfile)
    }

  val numDays = 5

  //  val signUpForm = Form(tuple(
  //    "freshFood" -> seq(boolean),
  //    "cleaning" -> seq(boolean)))

  type ManyBooleans = (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Seq[Int])

  // This hard-coding is a hack to work around an apparent Play bug.
  val signUpForm: Form[ManyBooleans] = Form(tuple(
    "freshFood0" -> boolean,
    "freshFood1" -> boolean,
    "freshFood2" -> boolean,
    "freshFood3" -> boolean,
    "freshFood4" -> boolean,
    "cleaning0" -> boolean,
    "cleaning1" -> boolean,
    "cleaning2" -> boolean,
    "cleaning3" -> boolean,
    "cleaning4" -> boolean,
    "meals" -> seq(number(0, 8))))

  def dates: Seq[LocalDate] = {
    val today = dateToday
    val allFollowing = Stream.from(0) map (today.plusDays)
    // Filter out Saturdays and Sundays.
    // The week is 1-indexed, with 1 -> Monday.
    allFollowing filter (_.getDayOfWeek <= 5) take (numDays) toList
  }

  def freshFoodVolunteers =
    dates map (Models.freshFoodSignUp.get) map (_.map(Models.users.apply))

  def cleaningVolunteers =
    dates map (Models.cleaningSignUp.get) map (_.map(Models.users.apply))

  def meals =
    dates map (Models.mealsSignUp.getOrElse(_, Map[IdentityId, Int]())) map {
      _.map {
        case (id, numMeals) => (Models.users.apply(id), numMeals)
      }
    }

  def getSignUp = SecuredAction { implicit request =>
    val user: SocialUser = request.user.asInstanceOf[SocialUser]

    if (Models.userInformation.contains(user.identityId)) {
      val signUpFormOpenings = {
        val freshFoodOpenings = freshFoodVolunteers map (_.isDefined)
        val cleaningOpenings = cleaningVolunteers map (_.isDefined)
        val userMeals = meals map {
          _.getOrElse(user, 0)
        }
        //      signUpForm.fill(freshFoodOpenings.toList, cleaningOpenings.toList)
        signUpForm.fill(
          freshFoodOpenings(0),
          freshFoodOpenings(1),
          freshFoodOpenings(2),
          freshFoodOpenings(3),
          freshFoodOpenings(4),
          cleaningOpenings(0),
          cleaningOpenings(1),
          cleaningOpenings(2),
          cleaningOpenings(3),
          cleaningOpenings(4),
          userMeals)
      }

      Ok(views.html.signUp(
        user,
        dates,
        freshFoodVolunteers,
        cleaningVolunteers,
        meals,
        signUpFormOpenings))
    } else {
      Redirect(fftsite.controllers.routes.FFTSite.getProfile)
    }
  }

  def postSignUp = SecuredAction { implicit request =>
    val user: SocialUser = request.user.asInstanceOf[SocialUser]

    signUpForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(views.html.signUp(
          user,
          dates,
          freshFoodVolunteers,
          cleaningVolunteers,
          meals,
          formWithErrors)),
      value => {
        val freshFoodCheckboxes = Seq(
          value._1,
          value._2,
          value._3,
          value._4,
          value._5)

        val cleaningCheckboxes = Seq(
          value._6,
          value._7,
          value._8,
          value._9,
          value._10)

        val mealCounts = value._11

        //        val (freshFoodCheckboxes, cleaningCheckboxes) = value

        def exclusiveSignUp(
          existingVolunteers: Seq[Option[SocialUser]],
          checkboxes: Seq[Boolean],
          persistentMap: PersistentMap[LocalDate, IdentityId]) {
          assert(existingVolunteers.size == numDays)
          println(checkboxes.size)
          assert(checkboxes.size == numDays)

          for (index <- 0 until numDays) {
            // If this user was already signed up but he unchecked the box.
            if (existingVolunteers(index) == Some(user) && checkboxes(index) == false) {
              persistentMap -= dates(index)
            }
            // Nobody was signed up, and this user checked the box.
            if (existingVolunteers(index) == None && checkboxes(index) == true) {
              persistentMap += dates(index) -> user.identityId
            }
          }
        }

        exclusiveSignUp(freshFoodVolunteers, freshFoodCheckboxes, Models.freshFoodSignUp)
        exclusiveSignUp(cleaningVolunteers, cleaningCheckboxes, Models.cleaningSignUp)

        for (index <- 0 until numDays) {
          val currentMealSignUps = Models.mealsSignUp.getOrElse(dates(index), Map[IdentityId, Int]())

          val newMealSignUps = {
            // If the user's meal count is zero, drop him from the map.
            if (mealCounts(index) == 0) currentMealSignUps - user.identityId
            else currentMealSignUps + (user.identityId -> mealCounts(index))
          }

          Models.mealsSignUp(dates(index)) = newMealSignUps

        }

        Redirect(fftsite.controllers.routes.FFTSite.getSignUp)
      })
  }

  //  val reimbursementRequestForm = Form(tuple(
  //    "date" -> jodaLocalDate,
  //    "expenseType" -> nonEmptyText,
  //    "amount" -> number(0, 10000),
  //    "notes" -> text))

  val reimbursementPartForm = Form(mapping(
    "date" -> jodaLocalDate,
    "expenseType" -> nonEmptyText,
    "amount" -> (bigDecimal verifying Constraints.min(0: BigDecimal, true)),
    "notes" -> text)(ReimbursementPart.apply)(ReimbursementPart.unapply))

  def getReimbursements = SecuredAction { implicit request =>
    val user: SocialUser = request.user.asInstanceOf[SocialUser]

    Ok(views.html.reimbursements(
      Models.reimbursementRequests.getOrElse(user.identityId, Nil).toList,
      reimbursementPartForm))
    //    Ok(views.html.reimbursements(
    //      Nil,
    //      reimbursementPartForm))
  }

  def getReimbursementsAll = Action { implicit request =>
    val reimbursements = Models.reimbursementRequests.toList
    val flattened = reimbursements.flatMap {
      case (identityId, requests) => {
        val user = Models.users(identityId)
        requests map ((user, _))
      }
    }

    Ok(views.html.reimbursementsAll(flattened))
  }

  def postReimbursements = UserAwareAction(parse.multipartFormData) { implicit request =>
    if (!request.user.isDefined) {
      Redirect(fftsite.controllers.routes.FFTSite.getReimbursements)
    } else {

      val user: SocialUser = request.user.get.asInstanceOf[SocialUser]

      request.body.file("receiptPhoto").map { picture =>
        reimbursementPartForm.bindFromRequest.fold(
          formWithErrors => BadRequest(views.html.reimbursements(
            Models.reimbursementRequests.getOrElse(user.identityId, Nil).toList,
            formWithErrors)),
          value => {
            val storageFile = File.createTempFile(
              "receipt",
              picture.filename,
              new File(new File(Play.application.path.getPath), "/public/receipts"))

            picture.ref.moveTo(storageFile, true)

            val reimbursementRequest = ReimbursementRequest(
              new util.Random().nextLong,
              value,
              storageFile.getName)

            val previousRequests = Models.reimbursementRequests.getOrElse(user.identityId, Nil)
            val newRequests = (reimbursementRequest +: previousRequests.toList).toList
            //          newRequests.pickle
            Models.reimbursementRequests += user.identityId -> newRequests.toSet

            Redirect(fftsite.controllers.routes.FFTSite.getReimbursements)
          })
      }.getOrElse(Redirect(fftsite.controllers.routes.FFTSite.getReimbursements))
    }
  }

  def getDeleteReimbursement(uuid: Long) = SecuredAction { implicit request =>
    val user: SocialUser = request.user.asInstanceOf[SocialUser]

    val oldRequests = Models.reimbursementRequests.getOrElse(user.identityId, Nil).toList
    val newRequests = oldRequests.filter(_.uuid != uuid)
    Models.reimbursementRequests += user.identityId -> newRequests.toSet

    Ok(views.html.reimbursements(
      Models.reimbursementRequests.getOrElse(user.identityId, Nil).toList,
      reimbursementPartForm))
  }

  def getToday = Action { implicit request =>
    val date = dates.head
    val freshFoodVolunteer = freshFoodVolunteers.head
    val cleaningVolunteer = cleaningVolunteers.head

    val eaters = (meals.head map {
      case (socialUser, numMeals) => (socialUser, numMeals, Models.dietaryInformation.get(socialUser.identityId))
    }).toSeq

    Ok(views.html.today(date, freshFoodVolunteer, cleaningVolunteer, eaters.sortBy(_._1.firstName)))
  }

  def getReports = Action { implicit request =>
    // TODO: Use the quarters config file to generate a list of quarters between
    // (2013, 1) and the current date.
    var quarterDates = getQuarterDates


    // find quarters with data
    var quarters = Set[YearAndQuarter]()
    for(reimbursements <- Models.reimbursementRequests.values.toList) {
      for(reimbursement <- reimbursements.toList) {
        for(quarter <- quarterDates.keys) {
          if(reimbursement.reimbursementPart.date.compareTo(quarterDates(quarter).beginDate) >= 0 &&
            reimbursement.reimbursementPart.date.compareTo(quarterDates(quarter).endDate) <= 0) {
            quarters.+=(quarter)
            //break
          }
        }
      }
    }


    Ok(views.html.reports(quarters.toList.sortWith((a,b) => (a.toSortable < b.toSortable))))
  }

  def getQuarterReport(year: Int, quarter: Int) = Action { implicit request =>
    val users = Models.users
    val quarterDates = getQuarterDates
    val beginDate = quarterDates(YearAndQuarter(year, quarter)).beginDate
    val endDate = quarterDates(YearAndQuarter(year, quarter)).endDate
    // find all reimbursement requests for this quarter

    var quarterReimbursementRequests = List[ReimbursementPart]()
    for (reimbursementRequestSet <- Models.reimbursementRequests.values.toList) {
      for (reimbursementRequest <- reimbursementRequestSet.toList) {
        if (reimbursementRequest.reimbursementPart.date.compareTo(beginDate) >= 0 &&
          reimbursementRequest.reimbursementPart.date.compareTo(endDate) <= 0 &&
          reimbursementRequest.reimbursementPart.expenseType == "Fresh food") {
          quarterReimbursementRequests :+= (reimbursementRequest.reimbursementPart)
        }
      }
    }
    // add up reimbursement requests per user
    var reportUsers = List[ReportUser]()
    //val writer = new PrintWriter(new File("fftdebug.log"))
    for (id <- Models.users.keySet) {
      //writer.println("============================================================================")
      //writer.println(id)
      var amount = BigDecimal(0)
      var date = beginDate
      var lineItems = List[ReportUserLineItem]()
      while (date.compareTo(endDate) != 0) {
        if (Models.mealsSignUp.contains(date)) {
          val signUps = Models.mealsSignUp(date)
          if (signUps.contains(id)) {
            //writer.println("    found id")
            val numMeals = signUps(id)

            var totalMeals = 0
            for (n <- signUps.values.toList) {
              totalMeals += n
            }

            // find receipt for that day  	      	
            for (reimbursementRequestPart <- quarterReimbursementRequests) {
              if (date.compareTo(reimbursementRequestPart.date) == 0 && numMeals > 0) {
                val individualAmount = numMeals.toFloat / totalMeals.toFloat * reimbursementRequestPart.amount
                val individualAmountStr = f"$individualAmount%.2f"
                amount += individualAmount
                //writer.println("      numMeals: " +numMeals+" totalmeals: " + totalMeals+" lineItemAmount: " + amount + " totalAmount: " +reimbursementRequestPart.amount)
                lineItems :+= (ReportUserLineItem(date, numMeals, totalMeals, reimbursementRequestPart.amount, individualAmountStr))
              }
            }

          }
        }
        date = date.plusDays(1)
      }

      if (amount > 0) {
        var amountStr = f"$amount%2.2f"
        var employmentType = "Not entered"
        if (Models.employmentHistory.contains(id)) {
          if (Models.employmentHistory(id).history.contains(YearAndQuarter(year, quarter))) {
            employmentType = Models.employmentHistory(id).history(YearAndQuarter(year, quarter)).status
          }
        }
        var reportUser = ReportUser(Models.users(id), employmentType, amount, amountStr, lineItems)
        reportUsers :+= (reportUser)
        //writer.println(reportUsers)
      }

    }
    //writer.close()

    val yearAndQuarter = YearAndQuarter(year, quarter)
    Ok(views.html.quarterReport(yearAndQuarter, reportUsers))
  }
}
