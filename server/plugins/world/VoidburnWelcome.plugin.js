const config = require('./data/voidburn-welcome.json');
const ANNOUNCED_ATTRIBUTE = 'voidburn-welcome:announced';
const COLOUR = '00d3a6';

function welcomeNewPlayer({ player }) {
  if (!player || player.isPlayerBot?.() || player.getAttribute('bot-skip-persistence') === true
      || (player.getProfile?.() ?? 'main') !== 'main' || player.getAttribute(ANNOUNCED_ATTRIBUTE)) return;
  const created = player.getCreationDate?.()?.getTime();
  player.setAttribute(ANNOUNCED_ATTRIBUTE, true);
  this.core.GameConstants.PLAYER_PERSISTENCE.requestSave(player, 'voidburn-welcome');
  // Existing characters are considered silently on their next login.
  if (!Number.isFinite(created) || created < Date.parse(config.createdAfter)) return;
  const name = String(player.getDisplayName()).replace(/[<>]/g, '');
  const message = `Welcome ${name} to Voidburn!`;
  this.core.World.getPlayers().forEach(recipient => {
    if (!recipient || recipient.isPlayerBot?.() || recipient.getAttribute('bot-skip-persistence') === true
        || recipient.isRegistered?.() === false) return;
    recipient.sendMessage(`<col=${COLOUR}>${message}</col>`);
    this.emitCustomEvent('notification:show', {
      player: recipient, title: 'Welcome to Voidburn', text: `<col=${COLOUR}>${message}</col>`,
      colour: Number.parseInt(COLOUR, 16),
    });
  });
}

module.exports = {
  name: 'VoidburnWelcome',
  register(api) {
    api.persistAttribute(ANNOUNCED_ATTRIBUTE, false);
    api.onCustomEvent('player:bootstrap-complete', welcomeNewPlayer.bind(api));
  },
};
