const { FriendsChatManager } = require('../interface/FriendsChatManager');
const config = require('./data/voidburn-global-chat.json');
const VALUABLE_LOOT_MINIMUM = 1_000_000;
const DEFAULT_CONSIDERED_ATTRIBUTE = 'voidburn-global-chat:default-considered';

function considerDefaultChannel({ player }) {
  if (player.isPlayerBot?.() || player.getAttribute(DEFAULT_CONSIDERED_ATTRIBUTE)) return;
  player.setAttribute(DEFAULT_CONSIDERED_ATTRIBUTE, true);
  // Existing accounts and saved raid-channel choices keep their previous behavior.
  if (player.getRelations().getFriendsChatLastOwner()) return;
  const created = player.getCreationDate().getTime();
  if (!Number.isFinite(created) || created < Date.parse(config.autoJoinCreatedAfter)) return;
  this.emitCustomEvent('friends-chat:join', { player, owner: config.owner, notify: true });
}

function broadcast(player, text) {
  if (!player.isPlayerBot?.()) FriendsChatManager.broadcastAchievement(player, text);
}

function questCompleted({ player, quest }) {
  const name = quest?.name ?? quest?.title;
  if (name) broadcast(player, `${player.getDisplayName()} has completed a quest: ${name}.`);
}

function collectionLog({ player, itemId }) {
  const name = this.core.ItemDefinition.forId(Number(itemId))?.getName();
  if (name) broadcast(player, `${player.getDisplayName()} received a new collection log item: ${name}.`);
}

function valuableLoot(request) {
  const { player } = request;
  if (!player || player.isPlayerBot?.()) return;
  // Other roll handlers can edit the reward list before it is awarded.
  queueMicrotask(() => {
    for (const item of request.drops ?? []) {
      const definition = this.core.ItemDefinition.forId(Number(item.itemId ?? item.id));
      const amount = item.amount ?? 1;
      const value = (definition?.getValue() ?? 0) * amount;
      if (value < VALUABLE_LOOT_MINIMUM) continue;
      const quantity = amount > 1 ? `${amount.toLocaleString('en-GB')} x ` : '';
      broadcast(player, `${player.getDisplayName()} received a drop: ${quantity}${definition.getName()} (${value.toLocaleString('en-GB')} coins).`);
    }
  });
}

module.exports = {
  name: 'VoidburnGlobalChat',
  register(api) {
    api.persistAttribute(DEFAULT_CONSIDERED_ATTRIBUTE, false);
    api.onPlayerLogin(considerDefaultChannel.bind(api));
    api.onCustomEvent('quest:completed', questCompleted);
    api.onCustomEvent('collection-log:new-item', collectionLog.bind(api));
    api.onCustomEvent('npc-drops:roll', valuableLoot.bind(api));
  },
};
