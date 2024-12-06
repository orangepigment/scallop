package org.rogach.scallop

import scala.collection.{Seq => CSeq}

trait ScallopArgListLoader {
  def loadArgList(args: CSeq[String], canReadFromFileOrStdIn: Boolean): CSeq[String] = args
}
