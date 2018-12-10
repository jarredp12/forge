package forge.ai;

import java.util.Iterator;
import java.util.List;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;

import forge.card.CardStateName;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates.Presets;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

public class ComputerUtilAbility {
    public static CardCollection getAvailableLandsToPlay(final Game game, final Player player) {
        if (!game.getStack().isEmpty() || !game.getPhaseHandler().getPhase().isMain()) {
            return null;
        }
        final CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        hand.addAll(player.getCardsIn(ZoneType.Exile));
        CardCollection landList = CardLists.filter(hand, Presets.LANDS);

        //filter out cards that can't be played
        landList = CardLists.filter(landList, new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                if (!c.getSVar("NeedsToPlay").isEmpty()) {
                    final String needsToPlay = c.getSVar("NeedsToPlay");
                    CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), needsToPlay.split(","), c.getController(), c, null);
                    if (list.isEmpty()) {
                        return false;
                    }
                }
                return player.canPlayLand(c);
            }
        });

        final CardCollection landsNotInHand = new CardCollection(player.getCardsIn(ZoneType.Graveyard));
        landsNotInHand.addAll(game.getCardsIn(ZoneType.Exile));
        if (!player.getCardsIn(ZoneType.Library).isEmpty()) {
            landsNotInHand.add(player.getCardsIn(ZoneType.Library).get(0));
        }
        for (final Card crd : landsNotInHand) {
            if (!(crd.isLand() || (crd.isFaceDown() && crd.getState(CardStateName.Original).getType().isLand()))) {
                continue;
            }
            if (!crd.mayPlay(player).isEmpty()) {
                landList.add(crd);
            }
        }
        if (landList.isEmpty()) {
            return null;
        }
        return landList;
    }

    public static CardCollection getAvailableCards(final Game game, final Player player) {
        CardCollection all = new CardCollection(player.getCardsIn(ZoneType.Hand));

        all.addAll(player.getCardsIn(ZoneType.Graveyard));
        all.addAll(player.getCardsIn(ZoneType.Command));
        if (!player.getCardsIn(ZoneType.Library).isEmpty()) {
            all.add(player.getCardsIn(ZoneType.Library).get(0));
        }
        for(Player p : game.getPlayers()) {
            all.addAll(p.getCardsIn(ZoneType.Exile));
            all.addAll(p.getCardsIn(ZoneType.Battlefield));
        }
        return all;
    }

    public static List<SpellAbility> getSpellAbilities(final CardCollectionView l, final Player player) {
        final List<SpellAbility> spellAbilities = Lists.newArrayList();
        for (final Card c : l) {
            for (final SpellAbility sa : c.getSpellAbilities()) {
                spellAbilities.add(sa);
            }
            if (c.isFaceDown() && c.isInZone(ZoneType.Exile) && !c.mayPlay(player).isEmpty()) {
                for (final SpellAbility sa : c.getState(CardStateName.Original).getSpellAbilities()) {
                    spellAbilities.add(sa);
                }
            }
        }
        return spellAbilities;
    }

    public static List<SpellAbility> getOriginalAndAltCostAbilities(final List<SpellAbility> originList, final Player player) {
        final List<SpellAbility> newAbilities = Lists.newArrayList();
        for (SpellAbility sa : originList) {
            sa.setActivatingPlayer(player);
            //add alternative costs as additional spell abilities
            newAbilities.add(sa);
            newAbilities.addAll(GameActionUtil.getAlternativeCosts(sa, player));
        }
    
        final List<SpellAbility> result = Lists.newArrayList();
        for (SpellAbility sa : newAbilities) {
            sa.setActivatingPlayer(player);
            result.addAll(GameActionUtil.getOptionalCosts(sa));
        }
        return result;
    }

    public static SpellAbility getTopSpellAbilityOnStack(Game game, SpellAbility sa) {
        Iterator<SpellAbilityStackInstance> it = game.getStack().iterator();

        if (!it.hasNext()) {
            return null;
        }

        SpellAbility tgtSA = it.next().getSpellAbility(true);
        // Grab the topmost spellability that isn't this SA and use that for comparisons
        if (sa.equals(tgtSA) && game.getStack().size() > 1) {
            if (!it.hasNext()) {
                return null;
            }
            tgtSA = it.next().getSpellAbility(true);
        }
        return tgtSA;
    }

    public static SpellAbility getFirstCopySASpell(List<SpellAbility> spells) {
        SpellAbility sa = null;
        for (SpellAbility spell : spells) {
            if (spell.getApi() == ApiType.CopySpellAbility) {
                sa = spell;
                break;
            }
        }
        return sa;
    }

    public static Card getAbilitySource(SpellAbility sa) {
        return sa.getOriginalHost() != null ? sa.getOriginalHost() : sa.getHostCard();
    }

    public static String getAbilitySourceName(SpellAbility sa) {
        final Card c = getAbilitySource(sa);
        return c != null ? c.getName() : "";
    }

    public static CardCollection getCardsTargetedWithApi(Player ai, CardCollection cardList, SpellAbility sa, ApiType api) {
        // Returns a collection of cards which have already been targeted with the given API either in the parent ability,
        // in the sub ability, or by something on stack. If "sa" is specified, the parent and sub abilities of this SA will
        // be checked for targets. If "sa" is null, only the stack instances will be checked.
        CardCollection targeted = new CardCollection();
        if (sa != null) {
            SpellAbility saSub = sa.getRootAbility();
            while (saSub != null) {
                if (saSub.getApi() == api && saSub.getTargets() != null) {
                    for (Card c : cardList) {
                        if (saSub.getTargets().getTargetCards().contains(c)) {
                            // Was already targeted with this API in a parent or sub SA
                            targeted.add(c);
                        }
                    }
                }
                saSub = saSub.getSubAbility();
            }
        }
        for (SpellAbilityStackInstance si : ai.getGame().getStack()) {
            SpellAbility ab = si.getSpellAbility(false);
            if (ab != null && ab.getApi() == api && si.getTargetChoices() != null) {
                for (Card c : cardList) {
                    // TODO: somehow ensure that the detected SA won't be countered
                    if (si.getTargetChoices().getTargetCards().contains(c)) {
                        // Was already targeted by a spell ability instance on stack
                        targeted.add(c);
                    }
                }
            }
        }

        return targeted;
    }

    public static Pair<SpellAbility, Integer> getDamageAfterChainingSpells(Player ai, SpellAbility sa, String damage) {
        int chance = ((PlayerControllerAi)ai.getController()).getAi().getIntProperty(AiProps.CHANCE_TO_CHAIN_TWO_DAMAGE_SPELLS);
        if (!MyRandom.percentTrue(chance)) {
            return null;
        }

        if (sa.getSubAbility() != null || sa.getParent() != null) {
            // Doesn't work yet for complex decisions where damage is only a part of the decision process
            return null;
        }

        // Try to chain damage/debuff effects
        if (StringUtils.isNumeric(damage) || (damage.startsWith("-") && StringUtils.isNumeric(damage.substring(1)))) {
            // currently only works for predictable numeric damage
            CardCollection cards = new CardCollection();
            cards.addAll(ai.getCardsIn(ZoneType.Hand));
            cards.addAll(ai.getCardsIn(ZoneType.Battlefield));
            cards.addAll(ai.getCardsActivableInExternalZones(true));
            for (Card c : cards) {
                for (SpellAbility ab : c.getSpellAbilities()) {
                    if (ab.equals(sa) || ab.getSubAbility() != null) { // decisions for complex SAs with subs are not supported yet
                        continue;
                    }
                    // currently works only with cards that don't have additional costs (only mana is supported)
                    if (ab.getPayCosts() != null && (ab.getPayCosts().hasNoManaCost() || ab.getPayCosts().hasOnlySpecificCostType(CostPartMana.class))) {
                        String dmgDef = "0";
                        if (ab.getApi() == ApiType.DealDamage) {
                            dmgDef = ab.getParamOrDefault("NumDmg", "0");
                        } else if (ab.getApi() == ApiType.Pump) {
                            dmgDef = ab.getParamOrDefault("NumDef", "0");
                            if (dmgDef.startsWith("-")) {
                                dmgDef = dmgDef.substring(1);
                            } else {
                                continue; // not a toughness debuff
                            }
                        }
                        if (StringUtils.isNumeric(dmgDef) && ab.canPlay()) { // currently doesn't work for X and other dependent costs
                            if (sa.usesTargeting() && ab.usesTargeting()) {
                                // Ensure that the chained spell can target at least the same things (or more) as the current one
                                TargetRestrictions tgtSa = sa.getTargetRestrictions();
                                TargetRestrictions tgtAb = sa.getTargetRestrictions();
                                if (tgtSa.canTgtCreature() && !tgtAb.canTgtCreature()) {
                                    continue;
                                } else if (tgtSa.canTgtPlaneswalker() && !tgtAb.canTgtPlaneswalker()) {
                                    continue;
                                }
                                // FIXME: should it also check restrictions for targeting players?
                                ManaCost costSa = sa.getPayCosts() != null ? sa.getPayCosts().getTotalMana() : ManaCost.NO_COST;
                                ManaCost costAb = ab.getPayCosts().getTotalMana(); // checked for null above
                                ManaCost total = ManaCost.combine(costSa, costAb);
                                SpellAbility combinedAb = ab.copyWithDefinedCost(new Cost(total, false));
                                // can we pay both costs?
                                if (ComputerUtilMana.canPayManaCost(combinedAb, ai, 0)) {
                                    //aic.reserveManaSourcesTillNextPriority(ab); // reserve mana for the second spell
                                    return Pair.of(ab, Integer.parseInt(dmgDef));
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }
}
