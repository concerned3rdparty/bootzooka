package com.softwaremill.bootzooka.service.email

import com.softwaremill.bootzooka.service.templates.EmailContentWithSubject

import scala.concurrent.Future

trait EmailScheduler {

  def scheduleEmail(address: String, emailData: EmailContentWithSubject): Future[Unit]

}
