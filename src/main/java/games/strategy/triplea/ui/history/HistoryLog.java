package games.strategy.triplea.ui.history;

import java.awt.BorderLayout;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.history.HistoryNode;
import games.strategy.engine.history.Renderable;
import games.strategy.engine.history.Round;
import games.strategy.engine.history.Step;
import games.strategy.engine.random.IRandomStats;
import games.strategy.engine.random.RandomStatsDetails;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.dataObjects.MoveDescription;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.util.IntegerMap;

public class HistoryLog extends JFrame {
  private static final long serialVersionUID = 4880602702815333376L;
  private final JTextArea textArea;
  private final StringWriter stringWriter;
  private final PrintWriter printWriter;

  public HistoryLog() {
    textArea = new JTextArea(40, 80);
    textArea.setEditable(false);
    final JScrollPane scrollingArea = new JScrollPane(textArea);
    // ... Get the content pane, set layout, add to center
    final JPanel content = new JPanel();
    content.setLayout(new BorderLayout());
    content.add(scrollingArea, BorderLayout.CENTER);
    stringWriter = new StringWriter();
    printWriter = new PrintWriter(stringWriter);
    // ... Set window characteristics.
    this.setContentPane(content);
    this.setTitle("History Log");
    this.pack();
    this.setLocationRelativeTo(null);
  }

  public PrintWriter getWriter() {
    return printWriter;
  }

  @Override
  public String toString() {
    return stringWriter.toString();
  }

  public void clear() {
    stringWriter.getBuffer().delete(0, stringWriter.getBuffer().length());
    textArea.setText("");
  }

  public void printFullTurn(final GameData data, final boolean verbose, final Collection<PlayerID> playersAllowed) {
    final HistoryNode printNode = data.getHistory().getLastNode();
    HistoryNode curNode = printNode;
    Step stepNode = null;
    Step turnStartNode = null;
    PlayerID curPlayer = null;
    final Collection<PlayerID> players = new HashSet<>();
    if (playersAllowed != null) {
      players.addAll(playersAllowed);
    }
    // find Step node, if exists in this path
    while (curNode != null) {
      if (curNode instanceof Step) {
        stepNode = (Step) curNode;
        break;
      }
      curNode = (HistoryNode) curNode.getPreviousNode();
    }
    if (stepNode != null) {
      curPlayer = stepNode.getPlayerId();
      if (players.isEmpty()) {
        players.add(curPlayer);
      }
      // get first step for this turn
      while (stepNode != null) {
        turnStartNode = stepNode;
        stepNode = (Step) stepNode.getPreviousSibling();
        if (stepNode == null) {
          break;
        }
        if (stepNode.getPlayerId() == null) {
          break;
        }
        if (!players.contains(stepNode.getPlayerId())) {
          break;
        }
      }
      printRemainingTurn(turnStartNode, verbose, data.getDiceSides(), players);
    } else {
      System.err.println("No Step node found!");
    }
  }

  private static PlayerID getPlayerId(final HistoryNode printNode) {
    DefaultMutableTreeNode curNode = printNode;
    final TreePath parentPath = (new TreePath(printNode.getPath())).getParentPath();
    PlayerID curPlayer = null;
    if (parentPath != null) {
      final Object[] pathToNode = parentPath.getPath();
      for (final Object pathNode : pathToNode) {
        final HistoryNode node = (HistoryNode) pathNode;
        if (node instanceof Step) {
          curPlayer = ((Step) node).getPlayerId();
        }
      }
    }
    do {
      final Enumeration<?> nodeEnum = curNode.preorderEnumeration();
      while (nodeEnum.hasMoreElements()) {
        final HistoryNode node = (HistoryNode) nodeEnum.nextElement();
        if (node instanceof Step) {
          final String title = node.getTitle();
          final PlayerID playerId = ((Step) node).getPlayerId();
          if (!title.equals("Initializing Delegates")) {
            if (playerId != null) {
              curPlayer = playerId;
            }
          }
        }
      }
      curNode = curNode.getNextSibling();
    } while ((curNode instanceof Step) && ((Step) curNode).getPlayerId().equals(curPlayer));
    return curPlayer;
  }

  @SuppressWarnings("unchecked")
  public void printRemainingTurn(final HistoryNode printNode, final boolean verbose, final int diceSides,
      final Collection<PlayerID> playersAllowed) {
    final PrintWriter logWriter = printWriter;
    final String moreIndent = "    ";
    // print out the parent nodes
    final TreePath parentPath = (new TreePath(printNode.getPath())).getParentPath();
    PlayerID currentPlayer = null;
    if (parentPath != null) {
      final Object[] pathToNode = parentPath.getPath();
      for (final Object pathNode : pathToNode) {
        final HistoryNode node = (HistoryNode) pathNode;
        for (int i = 0; i < node.getLevel(); i++) {
          logWriter.print(moreIndent);
        }
        logWriter.println(node.getTitle());
        if (node.getLevel() == 0) {
          logWriter.println();
        }
        if (node instanceof Step) {
          currentPlayer = ((Step) node).getPlayerId();
        }
      }
    }
    final Collection<PlayerID> players = new HashSet<>();
    if (playersAllowed != null) {
      players.addAll(playersAllowed);
    }
    if (currentPlayer != null) {
      players.add(currentPlayer);
    }
    final List<String> moveList = new ArrayList<>();
    boolean moving = false;
    DefaultMutableTreeNode curNode = printNode;
    do {
      // keep track of conquered territory during combat
      String conquerStr = "";
      final Enumeration<?> nodeEnum = curNode.preorderEnumeration();
      while (nodeEnum.hasMoreElements()) {
        final HistoryNode node = (HistoryNode) nodeEnum.nextElement();
        final String title = node.getTitle();
        String indent = "";
        for (int i = 0; i < node.getLevel(); i++) {
          indent = indent + moreIndent;
        }
        // flush move list
        if (moving && !(node instanceof Renderable)) {
          final Iterator<String> moveIter = moveList.iterator();
          while (moveIter.hasNext()) {
            logWriter.println(moveIter.next());
            moveIter.remove();
          }
          moving = false;
        }
        if (node instanceof Renderable) {
          final Object details = ((Renderable) node).getRenderingData();
          if (details instanceof DiceRoll) {
            if (!verbose) {
              continue;
            }
            final String diceMsg1 = title.substring(0, title.indexOf(':') + 1);
            if (diceMsg1.equals("")) {
              // tech roll
              logWriter.println(indent + moreIndent + title);
            } else {
              // dice roll
              // Japanese roll dice for 1 armour in Russia, round 1
              logWriter.print(indent + moreIndent + diceMsg1);
              final DiceRoll diceRoll = (DiceRoll) details;
              final int hits = diceRoll.getHits();
              int rolls = 0;
              for (int i = 1; i <= diceSides; i++) {
                rolls += diceRoll.getRolls(i).size();
              }
              logWriter.println("  " + hits + "/" + rolls + " hits");
            }
          } else if (details instanceof MoveDescription) {
            // movement
            final Pattern p = Pattern.compile("\\w+ undo move (\\d+).");
            final Matcher m = p.matcher(title);
            if (m.matches()) {
              moveList.remove(Integer.valueOf(m.group(1)) - 1);
            } else {
              moveList.add(indent + title);
              moving = true;
            }
          } else if (details instanceof Collection) {
            final Collection<Object> objects = (Collection<Object>) details;
            final Iterator<Object> objIter = objects.iterator();
            if (objIter.hasNext()) {
              final Object obj = objIter.next();
              if (obj instanceof Unit) {
                final Collection<Unit> allUnitsInDetails = (Collection<Unit>) details;
                // purchase/place units - don't need details
                Unit unit = (Unit) obj;
                if (title.matches("\\w+ buy .*")) {
                  logWriter.println(indent + title);
                } else if (title.matches("\\w+ attack with .*")) {
                  logWriter.println(indent + title);
                } else if (title.matches("\\w+ defend with .*")) {
                  logWriter.println(indent + title);
                } else if (title.matches("\\d+ \\w+ owned by the .*? lost .*")) {
                  if (!verbose) {
                    continue;
                  }
                  logWriter.println(indent + moreIndent + title);
                } else if (title.matches("\\d+ \\w+ owned by the .*? lost")) {
                  if (!verbose) {
                    continue;
                  }
                  logWriter.println(indent + moreIndent + title);
                } else if (title.startsWith("Battle casualty summary:")) {
                  // logWriter.println(indent+"CAS1: "+title);
                  logWriter.println(
                      indent + conquerStr + ". Battle score " + title.substring(title.indexOf("for attacker is")));
                  conquerStr = "";
                  // separate units by player and show casualty summary
                  final IntegerMap<PlayerID> unitCount = new IntegerMap<>();
                  unitCount.add(unit.getOwner(), 1);
                  while (objIter.hasNext()) {
                    unit = (Unit) objIter.next();
                    unitCount.add(unit.getOwner(), 1);
                  }
                  for (final PlayerID player : unitCount.keySet()) {
                    logWriter.println(indent + "Casualties for " + player.getName() + ": "
                        + MyFormatter.unitsToTextNoOwner(allUnitsInDetails, player));
                  }
                } else if (title.matches(".*? placed in .*")) {
                  logWriter.println(indent + title);
                } else if (title.matches(".* owned by the \\w+ retreated to .*")) {
                  logWriter.println(indent + title);
                } else if (title.matches("\\w+ win")) {
                  conquerStr =
                      title + conquerStr + " with " + MyFormatter.unitsToTextNoOwner(allUnitsInDetails) + " remaining";
                } else {
                  logWriter.println(indent + title);
                }
              } else {
                // collection of unhandled objects
                logWriter.println(indent + title);
              }
            } else {
              // empty collection of something
              if (title.matches("\\w+ win")) {
                conquerStr = title + conquerStr + " with no units remaining";
              } else {
                // empty collection of unhandled objects
                logWriter.println(indent + title);
              }
            }
          } else if (details instanceof Territory) {
            // territory details
            logWriter.println(indent + title);
          } else if (details == null) {
            if (title.equals("Adding original owners")) {
              // do nothing
            } else if (title.equals(MoveDelegate.CLEANING_UP_DURING_MOVEMENT_PHASE)) {
              // do nothing
            } else if (title.equals("Game Loaded")) {
              // do nothing
            } else if (title.contains("now being played by")) {
              // do nothing
            } else if (title.contains("Turn Summary") || title.contains("Move Summary")) {
              // do nothing
            } else if (title.contains("Setting uses for triggers used")) {
              // do nothing
            } else if (title.equals("Resetting and Giving Bonus Movement to Units")) {
              // do nothing
            } else if (title.equals("Recording Battle Statistics")) {
              // do nothing
            } else if (title.equals("Preparing Airbases for Possible Scrambling")) {
              // do nothing
            } else if (title.matches("\\w+ collect \\d+ PUs?.*")) {
              logWriter.println(indent + title);
            } else if (title.matches("\\w+ takes? .*? from \\w+")) {
              // British take Libya from Germans
              if (moving) {
                final String str = moveList.remove(moveList.size() - 1);
                moveList.add(str + "\n  " + indent + title.replaceAll(" takes ", " take "));
              } else {
                conquerStr += title.replaceAll("^\\w+ takes ", ", taking ");
              }
            } else if (title.matches("\\w+ spend \\d+ on tech rolls")) {
              logWriter.println(indent + title);
            } else if (title.startsWith("Rolls to resolve tech hits:")) {
              // do nothing
            } else if (title.matches("\\w+ discover .*")) {
              logWriter.println(indent + title);
            } else if (title.matches("AA raid costs .*")) {
              logWriter.println(indent + title);
            } else {
              // unhandled message with null details
              logWriter.println(indent + title);
            }
          } else {
            // unknown details object
            logWriter.println(indent + title);
          }
        } else if (node instanceof Step) {
          final PlayerID playerId = ((Step) node).getPlayerId();
          if (!title.equals("Initializing Delegates")) {
            logWriter.println();
            logWriter.print(indent + title);
            if (playerId != null) {
              currentPlayer = playerId;
              players.add(currentPlayer);
              logWriter.print(" - " + playerId.getName());
            }
            logWriter.println();
          }
        } else if (node instanceof Round) {
          logWriter.println();
          logWriter.println(indent + title);
        } else if (title.equals("Game History")) {
          logWriter.println(indent + title);
        } else {
          // unknown node type
          logWriter.println(indent + title);
        }
      } // while (nodeEnum.hasMoreElements())
      curNode = curNode.getNextSibling();
    } while (curNode != null && (curNode instanceof Step) && players.contains(((Step) curNode).getPlayerId()));
    // if we are mid-phase, this might not get flushed
    if (moving && !moveList.isEmpty()) {
      final Iterator<String> moveIter = moveList.iterator();
      while (moveIter.hasNext()) {
        logWriter.println(moveIter.next());
        moveIter.remove();
      }
      moving = false;
    }
    logWriter.println();
    logWriter.println();
    textArea.setText(stringWriter.toString());
  }

  public void printTerritorySummary(final HistoryNode printNode, final GameData data) {
    Collection<Territory> territories;
    final PlayerID player = getPlayerId(printNode);
    data.acquireReadLock();
    try {
      territories = data.getMap().getTerritories();
    } finally {
      data.releaseReadLock();
    }
    final Collection<PlayerID> players = new HashSet<>();
    players.add(player);
    printTerritorySummary(players, territories);
  }

  private void printTerritorySummary(final GameData data) {
    Collection<Territory> territories;
    PlayerID player;
    data.acquireReadLock();
    try {
      player = data.getSequence().getStep().getPlayerId();
      territories = data.getMap().getTerritories();
    } finally {
      data.releaseReadLock();
    }
    final Collection<PlayerID> players = new HashSet<>();
    players.add(player);
    printTerritorySummary(players, territories);
  }

  public void printTerritorySummary(final GameData data, final Collection<PlayerID> allowedPlayers) {
    if (allowedPlayers == null || allowedPlayers.isEmpty()) {
      printTerritorySummary(data);
      return;
    }
    Collection<Territory> territories;
    data.acquireReadLock();
    try {
      territories = data.getMap().getTerritories();
    } finally {
      data.releaseReadLock();
    }
    printTerritorySummary(allowedPlayers, territories);
  }

  private void printTerritorySummary(final Collection<PlayerID> players,
      final Collection<Territory> territories) {
    if (players == null || players.isEmpty() || territories == null || territories.isEmpty()) {
      return;
    }
    final PrintWriter logWriter = printWriter;
    // print all units in all territories, including "flags"
    logWriter.println("Territory Summary for " + MyFormatter.defaultNamedToTextList(players) + " : \n");
    for (final Territory t : territories) {
      final List<Unit> ownedUnits = t.getUnits().getMatches(Matches.unitIsOwnedByOfAnyOfThesePlayers(players));
      // see if there's a flag
      final TerritoryAttachment ta = TerritoryAttachment.get(t);
      boolean hasFlag = false;
      if (ta == null) {
        hasFlag = false;
      } else {
        hasFlag = t.getOwner() != null && players.contains(t.getOwner())
            && (ta.getOriginalOwner() == null || !players.contains(ta.getOriginalOwner()));
      }
      if (hasFlag || !ownedUnits.isEmpty()) {
        logWriter.print("    " + t.getName() + " : ");
        if (hasFlag && ownedUnits.isEmpty()) {
          logWriter.println("1 flag");
        } else if (hasFlag) {
          logWriter.print("1 flag, ");
        }
        // else if (ownedUnits.isEmpty())
        // logWriter.print("nothing");
        if (!ownedUnits.isEmpty()) {
          logWriter.println(MyFormatter.unitsToTextNoOwner(ownedUnits));
        }
      }
    }
    logWriter.println();
    logWriter.println();
    textArea.setText(stringWriter.toString());
  }

  public void printDiceStatistics(final GameData data, final IRandomStats randomStats) {
    final PrintWriter logWriter = printWriter;
    final RandomStatsDetails stats = randomStats.getRandomStats(data.getDiceSides());
    final String diceStats = stats.getAllStatsString("    ");
    if (diceStats.length() > 0) {
      logWriter.println(diceStats);
      logWriter.println();
      logWriter.println();
    }
    textArea.setText(stringWriter.toString());
  }

  public void printProductionSummary(final GameData data) {
    final PrintWriter logWriter = printWriter;
    Collection<PlayerID> players;
    Resource pus;
    data.acquireReadLock();
    try {
      pus = data.getResourceList().getResource(Constants.PUS);
      players = data.getPlayerList().getPlayers();
    } finally {
      data.releaseReadLock();
    }
    if (pus == null) {
      return;
    }
    logWriter.println("Production/PUs Summary :\n");
    for (final PlayerID player : players) {
      final int pusQuantity = player.getResources().getQuantity(pus);
      final int production = getProduction(player, data);
      logWriter.println("    " + player.getName() + " : " + production + " / " + pusQuantity);
    }
    logWriter.println();
    logWriter.println();
    textArea.setText(stringWriter.toString());
  }

  // copied from StatPanel
  private static int getProduction(final PlayerID player, final GameData data) {
    int production = 0;
    final Iterator<Territory> iter = data.getMap().getTerritories().iterator();
    while (iter.hasNext()) {
      boolean isConvoyOrLand = false;
      final Territory place = iter.next();
      final TerritoryAttachment ta = TerritoryAttachment.get(place);
      if (!place.isWater()) {
        isConvoyOrLand = true;
      } else if (place.isWater() && ta != null && OriginalOwnerTracker.getOriginalOwner(place) != PlayerID.NULL_PLAYERID
          && OriginalOwnerTracker.getOriginalOwner(place) == player && place.getOwner().equals(player)) {
        isConvoyOrLand = true;
      }
      if (place.getOwner().equals(player) && isConvoyOrLand) {
        if (ta != null) {
          production += ta.getProduction();
        }
      }
    }
    return production;
  }
}
