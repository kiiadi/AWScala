package awscala.simpledb

import awscala._
import scala.collection.JavaConverters._
import com.amazonaws.services.{ simpledb => aws }
import com.amazonaws.services.simpledb.model.ListDomainsRequest

object SimpleDB {

  def apply(credentials: Credentials = CredentialsLoader.load()): SimpleDB = new SimpleDBClient(credentials)
  def apply(accessKeyId: String, secretAccessKey: String): SimpleDB = apply(Credentials(accessKeyId, secretAccessKey))

  def at(region: Region): SimpleDB = apply().at(region)
}

/**
 * Amazon SimpleDB Java client wrapper
 * @see "http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/"
 */
trait SimpleDB extends aws.AmazonSimpleDB {

  def at(region: Region): SimpleDB = {
    this.setRegion(region)
    this
  }

  // ------------------------------------------
  // Domains
  // ------------------------------------------

  def domains: Seq[Domain] = {
    import com.amazonaws.services.simpledb.model.ListDomainsResult

    object domainsSequencer extends Sequencer[Domain,ListDomainsResult,String] {
      val baseRequest = new ListDomainsRequest()
      def getInitial = listDomains(baseRequest)
      def getMarker(r: ListDomainsResult)= r.getNextToken()
      def getFromMarker(marker: String) = listDomains(baseRequest.withNextToken(marker))
      def getList(r: ListDomainsResult) = (r.getDomainNames().asScala.toList map { x => Domain(x) }).asJava
    } 
    domainsSequencer.sequence 
  }
  
  def domain(name: String): Option[Domain] = domains.find(_.name == name)

  def domainMetadata(domain: Domain): DomainMetadata = {
    DomainMetadata(domainMetadata(new aws.model.DomainMetadataRequest().withDomainName(domain.name)))
  }

  def createDomain(name: String): Domain = {
    createDomain(new aws.model.CreateDomainRequest().withDomainName(name))
    Domain(name)
  }

  def deleteDomain(domain: Domain): Unit = deleteDomain(new aws.model.DeleteDomainRequest().withDomainName(domain.name))

  // ------------------------------------------
  // Items/Attributes
  // ------------------------------------------

  def select(domain: Domain, expression: String, consistentRead: Boolean = false): Seq[Item] = {
    
    case class State(items: List[Item], nextToken: Option[String])

    @scala.annotation.tailrec
    def next(state: State): (Option[Item], State) = state match {
      case State(head :: tail, nextToken) => (Some(Item(domain, head)), State(tail, nextToken))
      case State(Nil, Some(nextToken)) => {
        val result = select(new aws.model.SelectRequest() .withSelectExpression(expression) .withConsistentRead(consistentRead)).withNextToken(nextToken)
        next(State(result.getItems().asScala.map(i => Item(domain, i)).toList, Option(result.getNextToken())))
      }
      case State(Nil, None) => (None, state)
    }

    def toStream(state: State): Stream[Item] =
      next(state) match {
        case (Some(item), nextState) => Stream.cons(item, toStream(nextState))
        case (None, _) => Stream.Empty
      }

    val result = select(new aws.model.SelectRequest().withSelectExpression(expression) .withConsistentRead(consistentRead))
    toStream(State(result.getItems().asScala.map(i => Item(domain, i)).toList, Option(result.getNextToken())))   
  }

  def attributes(item: Item): Seq[Attribute] = {
    getAttributes(
      new aws.model.GetAttributesRequest().withDomainName(item.domain.name).withItemName(item.name))
      .getAttributes.asScala.map(as => Attribute(item, as)).toSeq
  }

  def replaceAttributesIfExists(item: Item, attributes: (String, String)*): Unit = {
    putAttributes(new aws.model.PutAttributesRequest()
      .withDomainName(item.domain.name)
      .withItemName(item.name)
      .withAttributes(attributes.map {
        case (k, v) =>
          new aws.model.ReplaceableAttribute().withName(k).withValue(v).withReplace(true)
      }.asJava))
  }

  def putAttributes(item: Item, attributes: (String, String)*): Unit = {
    putAttributes(new aws.model.PutAttributesRequest()
      .withDomainName(item.domain.name)
      .withItemName(item.name)
      .withAttributes(attributes.map {
        case (k, v) =>
          new aws.model.ReplaceableAttribute().withName(k).withValue(v).withReplace(false)
      }.asJava))
  }

  def deleteItems(items: Seq[Item]): Unit = {
    items.headOption.foreach { item =>
      batchDeleteAttributes(new aws.model.BatchDeleteAttributesRequest()
        .withDomainName(item.domain.name)
        .withItems(items.map(i => new aws.model.DeletableItem().withName(i.name)).asJava))
    }
  }

  def deleteAttributes(attributes: Seq[Attribute]): Unit = {
    attributes.headOption.foreach { attr =>
      deleteAttributes(new aws.model.DeleteAttributesRequest()
        .withItemName(attr.item.name)
        .withAttributes(attributes.map(_.asInstanceOf[aws.model.Attribute]).asJava))
    }
  }

}

/**
 * Default Implementation
 *
 * @param credentials credentials
 */
class SimpleDBClient(credentials: Credentials = CredentialsLoader.load())
  extends aws.AmazonSimpleDBClient(credentials)
  with SimpleDB

