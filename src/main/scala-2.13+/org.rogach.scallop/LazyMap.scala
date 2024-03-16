package org.rogach.scallop

/** A class that lazily encapsulates a map inside.
  */
class LazyMap[A,+B](
  under: => Map[A,B],
  _name: String,
  _isSupplied: () => Boolean,
  private[scallop] val cliOption: Option[CliOption]
) extends Map[A,B] with ScallopOptionBase {
  private[this] lazy val m = under
  def get(key: A) = m.get(key)
  def iterator = m.iterator
  def removed(key: A) = m.removed(key)
  def updated[B1 >: B](key: A, value: B1) = m.updated(key, value)
  def name: String = _name
  def isSupplied: Boolean = _isSupplied()
}
