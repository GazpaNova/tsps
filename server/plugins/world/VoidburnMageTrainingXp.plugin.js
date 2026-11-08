const Arena = require('../minigames/magetrainingarena/Common.MageTrainingArena');
const TRAINING_ROOMS = ['telekinetic', 'enchanting', 'alchemists', 'graveyard'];
const XP_MULTIPLIER = 2;

function multiplyArenaXp(event) {
  if (event.skill !== this.core.Skill.MAGIC || !Arena.data) return;
  const location = event.player.getLocation();
  const training = TRAINING_ROOMS.some(name => {
    const room = Arena.data[name];
    return Arena.inBox(location, room.bounds, room.levels ?? null);
  });
  if (training) event.multiplier *= XP_MULTIPLIER;
}

module.exports = {
  name: 'VoidburnMageTrainingXp',
  register(api) {
    api.onCanGainExperience(multiplyArenaXp.bind(api));
  },
};
