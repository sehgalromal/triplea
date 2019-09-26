package games.strategy.engine.chat;

import games.strategy.engine.chat.IChatController.Tag;
import games.strategy.engine.lobby.PlayerName;
import games.strategy.engine.message.MessageContext;
import games.strategy.engine.message.RemoteName;
import games.strategy.net.INode;
import games.strategy.net.Messengers;
import games.strategy.sound.ClipPlayer;
import games.strategy.sound.SoundPath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.triplea.java.Interruptibles;
import org.triplea.util.Tuple;

/**
 * chat logic.
 *
 * <p>A chat can be bound to multiple chat panels.
 */
public class Chat {
  private static final String TAG_MODERATOR = "[Mod]";

  private final List<IChatListener> listeners = new CopyOnWriteArrayList<>();
  private final Messengers messengers;
  private final String chatChannelName;
  private final String chatName;
  private final SentMessagesHistory sentMessages;
  private final long chatInitVersion;
  // mutex used for access synchronization to nodes
  // TODO: check if this mutex is used for something else as well
  private final Object mutexNodes = new Object();
  private final List<INode> nodes;
  private final CountDownLatch latch = new CountDownLatch(1);
  private final List<ChatMessage> chatHistory = new ArrayList<>();
  private final StatusManager statusManager;
  private final ChatIgnoreList ignoreList = new ChatIgnoreList();
  private final Map<INode, Set<String>> notesMap = new HashMap<>();
  private final ChatSoundProfile chatSoundProfile;
  private final IChatChannel chatChannelSubscriber =
      new IChatChannel() {
        private void assertMessageFromServer() {
          final INode senderNode = MessageContext.getSender();
          final INode serverNode = messengers.getServerNode();
          // this will happen if the message is queued
          // but to queue a message, we must first test where it came from
          // so it is safe in this case to return ok
          if (senderNode == null) {
            return;
          }
          if (!senderNode.equals(serverNode)) {
            throw new IllegalStateException(
                "The node:" + senderNode + " sent a message as the server!");
          }
        }

        @Override
        public void chatOccurred(final String message) {
          final PlayerName from = MessageContext.getSender().getPlayerName();
          if (isIgnored(from)) {
            return;
          }
          synchronized (mutexNodes) {
            chatHistory.add(new ChatMessage(message, from));
            for (final IChatListener listener : listeners) {
              listener.addMessage(message, from);
            }
            // limit the number of messages in our history.
            while (chatHistory.size() > 1000) {
              chatHistory.remove(0);
            }
          }
        }

        @Override
        public void speakerAdded(final INode node, final Tag tag, final long version) {
          assertMessageFromServer();
          // Ignore first speaker, it's ourselves.
          if (version == 1) {
            return;
          }
          Interruptibles.await(latch);
          if (version > chatInitVersion) {
            synchronized (mutexNodes) {
              nodes.add(node);
              addToNotesMap(node, tag);
              updateConnections();
            }
            for (final IChatListener listener : listeners) {
              listener.addStatusMessage(node.getPlayerName() + " has joined");
              if (chatSoundProfile == ChatSoundProfile.GAME_CHATROOM) {
                ClipPlayer.play(SoundPath.CLIP_CHAT_JOIN_GAME);
              }
            }
          }
        }

        @Override
        public void speakerRemoved(final INode node, final long version) {
          assertMessageFromServer();
          Interruptibles.await(latch);
          if (version > chatInitVersion) {
            synchronized (mutexNodes) {
              nodes.remove(node);
              notesMap.remove(node);
              updateConnections();
            }
            for (final IChatListener listener : listeners) {
              listener.addStatusMessage(node.getPlayerName() + " has left");
            }
          }
        }

        @Override
        public void slapOccurred(final PlayerName to) {
          final PlayerName from = MessageContext.getSender().getPlayerName();
          if (isIgnored(from)) {
            return;
          }
          synchronized (mutexNodes) {
            if (to.equals(messengers.getLocalNode().getPlayerName())) {
              handleSlap("You were slapped by " + from, from);
            } else if (from.equals(messengers.getLocalNode().getPlayerName())) {
              handleSlap("You just slapped " + to, from);
            }
          }
        }

        private void handleSlap(final String message, final PlayerName from) {
          for (final IChatListener listener : listeners) {
            chatHistory.add(new ChatMessage(message, from));
            listener.addMessageWithSound(message, from, SoundPath.CLIP_CHAT_SLAP);
          }
        }

        @Override
        public void ping() {}
      };

  /** A profile defines the sounds to use for various chat events. */
  public enum ChatSoundProfile {
    LOBBY_CHATROOM,
    GAME_CHATROOM,
    NO_SOUND
  }

  public Chat(
      final Messengers messengers, final String chatName, final ChatSoundProfile chatSoundProfile) {
    this.chatSoundProfile = chatSoundProfile;
    this.messengers = messengers;
    statusManager = new StatusManager(messengers);
    chatChannelName = ChatController.getChatChannelName(chatName);
    this.chatName = chatName;
    sentMessages = new SentMessagesHistory();

    /*
     * The order of events is significant.
     *
     * 1. Register our channel listener. Once the channel is registered, we are guaranteed that
     * when we receive the response from our init message, our channel subscriber has been added,
     * and will see any messages broadcasted by the server.
     *
     * 2. Call the init message on the server remote. Any add or join messages sent from the server
     * will wait until we receive the init return value.
     *
     * 3. When we receive the init message response, initialize our state and release the latch
     * so all pending messages will get processed. Messages may be ignored if the server
     * version is incorrect. This all seems a lot more involved than it needs to be.
     */
    final IChatController controller = messengers.getRemoteChatController(chatName);
    messengers.addChatChannelSubscriber(chatChannelSubscriber, chatChannelName);
    final Tuple<Map<INode, Tag>, Long> init = controller.joinChat();
    final Map<INode, Tag> chatters = init.getFirst();
    nodes = new ArrayList<>(chatters.keySet());
    chatInitVersion = init.getSecond();
    latch.countDown();
    assignNodeTags(chatters);
    updateConnections();
  }

  private void updateConnections() {
    synchronized (mutexNodes) {
      if (nodes == null) {
        return;
      }
      final List<INode> playerNames = new ArrayList<>(nodes);
      Collections.sort(playerNames);
      for (final IChatListener listener : listeners) {
        listener.updatePlayerList(playerNames);
      }
    }
  }

  private void addToNotesMap(final INode node, final Tag tag) {
    if (tag == Tag.NONE) {
      return;
    }
    final LinkedHashSet<String> current = getTagText(tag);
    notesMap.put(node, current);
  }

  private static LinkedHashSet<String> getTagText(final Tag tag) {
    if (tag == Tag.NONE) {
      return null;
    }
    final LinkedHashSet<String> tagText = new LinkedHashSet<>();
    if (tag == Tag.MODERATOR) {
      tagText.add(TAG_MODERATOR);
    }
    // add more here....
    return tagText;
  }

  String getNotesForNode(final INode node) {
    final Set<String> notes = notesMap.get(node);
    if (notes == null) {
      return null;
    }
    final StringBuilder sb = new StringBuilder();
    for (final String note : notes) {
      sb.append(" ");
      sb.append(note);
    }
    return sb.toString();
  }

  SentMessagesHistory getSentMessagesHistory() {
    return sentMessages;
  }

  void addChatListener(final IChatListener listener) {
    listeners.add(listener);
    updateConnections();
  }

  StatusManager getStatusManager() {
    return statusManager;
  }

  void removeChatListener(final IChatListener listener) {
    listeners.remove(listener);
  }

  Object getMutex() {
    return mutexNodes;
  }

  /**
   * Call only when mutex for node is locked.
   *
   * @param chatters map from node to tag
   */
  private void assignNodeTags(final Map<INode, Tag> chatters) {
    for (final Map.Entry<INode, Tag> entry : chatters.entrySet()) {
      addToNotesMap(entry.getKey(), entry.getValue());
    }
  }

  /** Stop receiving events from the messenger. */
  public void shutdown() {
    messengers.unregisterChannelSubscriber(
        chatChannelSubscriber, new RemoteName(chatChannelName, IChatChannel.class));
    if (messengers.isConnected()) {
      final RemoteName chatControllerName = ChatController.getChatControllerRemoteName(chatName);
      final IChatController controller = (IChatController) messengers.getRemote(chatControllerName);
      controller.leaveChat();
    }
  }

  void sendSlap(final PlayerName playerName) {
    final IChatChannel remote =
        (IChatChannel)
            messengers.getChannelBroadcaster(new RemoteName(chatChannelName, IChatChannel.class));
    remote.slapOccurred(playerName);
  }

  public void sendMessage(final String message) {
    final IChatChannel remote =
        (IChatChannel)
            messengers.getChannelBroadcaster(new RemoteName(chatChannelName, IChatChannel.class));
    remote.chatOccurred(message);
    sentMessages.append(message);
  }

  void setIgnored(final PlayerName playerName, final boolean isIgnored) {
    if (isIgnored) {
      ignoreList.add(playerName);
    } else {
      ignoreList.remove(playerName);
    }
  }

  boolean isIgnored(final PlayerName playerName) {
    return ignoreList.shouldIgnore(playerName);
  }

  public INode getLocalNode() {
    return messengers.getLocalNode();
  }

  public INode getServerNode() {
    return messengers.getServerNode();
  }

  Collection<PlayerName> getOnlinePlayers() {
    return nodes.stream().map(INode::getPlayerName).collect(Collectors.toSet());
  }

  /**
   * While using this, you should synchronize on getMutex().
   *
   * @return the messages that have occurred so far.
   */
  List<ChatMessage> getChatHistory() {
    return chatHistory;
  }
}
