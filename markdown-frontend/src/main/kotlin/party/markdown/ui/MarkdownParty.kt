package party.markdown.ui

import io.github.alexandrepiveteau.echo.Exchange
import io.github.alexandrepiveteau.echo.MutableSite
import io.github.alexandrepiveteau.echo.protocol.Message.Incoming
import io.github.alexandrepiveteau.echo.protocol.Message.Outgoing
import party.markdown.MarkdownParty
import party.markdown.MarkdownPartyEvent
import party.markdown.data.tree.MutableSiteTreeApi
import party.markdown.data.tree.TreeApi
import party.markdown.ui.topBar.topBar
import react.*
import react.dom.div

external interface MarkdownPartyProps : RProps {
  var publicLink: String
  var local: MutableSite<MarkdownPartyEvent, MarkdownParty>
  var remote: Exchange<Incoming, Outgoing>
}

private val app =
    functionalComponent<MarkdownPartyProps> { props ->

      // TODO : Inject this ?
      val api = useMemo<TreeApi>(listOf(props.local)) { MutableSiteTreeApi(props.local) }
      val (debug, setDebug) = useState(false)

      div(classes = "flex flex-col h-screen w-screen") {
        topBar {
          publicLink = props.publicLink
          local = props.local
          remote = props.remote
          debugEnabled = debug
          onDebugEnabled = setDebug
        }
        dividerVertical()
        panes { treeApi = api }
      }
    }

fun RBuilder.markdownParty(
    block: MarkdownPartyProps.() -> Unit,
): ReactElement = child(app) { attrs(block) }
