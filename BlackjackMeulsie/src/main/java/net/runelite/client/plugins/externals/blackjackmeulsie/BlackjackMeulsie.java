/*
 * Copyright (c) 2018 gazivodag <https://github.com/gazivodag>
 * Copyright (c) 2019 lucwousin <https://github.com/lucwousin>
 * Copyright (c) 2019 infinitay <https://github.com/Infinitay>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.externals.blackjackmeulsie;

import com.google.inject.Provides;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.api.util.Text;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.AbstractComparableEntry;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.plugins.externals.utils.ExtUtils;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.input.KeyManager;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomUtils;
import org.pf4j.Extension;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Authors gazivodag longstreet
 */
@Extension
@PluginDescriptor(
	name = "BlackjackMeulsie",
	enabledByDefault = false,
	description = "Allows for one-click blackjacking, both knocking out and pickpocketing",
	tags = {"blackjack", "thieving"},
	type = PluginType.SKILLING
)
@Slf4j
public class BlackjackMeulsie extends Plugin
{
	private static final int POLLNIVNEACH_REGION = 13358;

	private static final String SUCCESS_BLACKJACK = "You smack the bandit over the head and render them unconscious.";
	private static final String FAILED_BLACKJACK = "Your blow only glances off the bandit's head.";

	private static final String PICKPOCKET = "Pickpocket";
	private static final String KNOCK_OUT = "Knock-out";
	private static final String BANDIT = "Bandit";
	private static final String MENAPHITE = "Menaphite Thug";

	private static final AbstractComparableEntry PICKPOCKET_BANDIT = new BJComparableEntry(BANDIT, true);
	private static final AbstractComparableEntry KNOCKOUT_BANDIT = new BJComparableEntry(BANDIT, false);
	private static final AbstractComparableEntry PICKPOCKET_MENAPHITE = new BJComparableEntry(MENAPHITE, true);
	private static final AbstractComparableEntry KNOCKOUT_MENAPHITE = new BJComparableEntry(MENAPHITE, false);

	@Inject
	private Client client;

	@Inject
	private BlackjackMeulsieConfig config;

	@Inject
	private EventBus eventBus;

	@Inject
	private MenuManager menuManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ExtUtils extUtils;

	private long nextKnockOutTick = 0;
	private static final int BLACKJACK_ID = 3550;
	private List<NPC> npcList = new ArrayList<>();
	private NPC closestNpc;
	private Point point;
	private Rectangle npcRect;
	private boolean run;

	@Provides
	BlackjackMeulsieConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BlackjackMeulsieConfig.class);
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(hotkeyListener);
		menuManager.addPriorityEntry(KNOCKOUT_BANDIT);
		menuManager.addPriorityEntry(KNOCKOUT_MENAPHITE);
	}

	@Override
	protected void shutDown()
	{
		menuManager.removePriorityEntry(PICKPOCKET_BANDIT);
		menuManager.removePriorityEntry(PICKPOCKET_MENAPHITE);
		menuManager.removePriorityEntry(KNOCKOUT_BANDIT);
		menuManager.removePriorityEntry(KNOCKOUT_MENAPHITE);
		keyManager.unregisterKeyListener(hotkeyListener);
		//eventBus.unregister("poll");
	}

	private HotkeyListener hotkeyListener = new HotkeyListener(() -> config.toggle()) {
		@Override
		public void hotkeyPressed()
		{
			if (run) {
				/*point = client.getMouseCanvasPosition();
				log.info(point.toString() + " captured as click point");*/
				log.info("pausing...");
				run = false;
			} else {
				//point = null;
				log.info("resuming...");
				run = true;
			}
		}
	};

/*	@Subscribe
	private void onNpcSpawned(NpcSpawned npcSpawned)
	{
		final NPC npc = npcSpawned.getNpc();

		if (npc.getId() == BLACKJACK_ID)
		{
			npcList.add(npc);
		}
	}

	@Subscribe
	private void onNpcDespawned(NpcDespawned npcDespawned)
	{
		final NPC npc = npcDespawned.getNpc();

		npcList.remove(npc);
	}*/

	@Subscribe
	private void onGameTick(GameTick event)
	{
		if(client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			log.info("not logged in.");
			return;
		} else {
			if (client.getTickCount() >= nextKnockOutTick) {
				menuManager.removePriorityEntry(PICKPOCKET_BANDIT);
				menuManager.removePriorityEntry(PICKPOCKET_MENAPHITE);
				menuManager.addPriorityEntry(KNOCKOUT_BANDIT);
				menuManager.addPriorityEntry(KNOCKOUT_MENAPHITE);
			}
			if (run) {
				if (checkHitpoints()) {
					if (config.flash()){
						//setFlash(true);
					}
					try
					{
						Thread.sleep(extUtils.getRandomIntBetweenRange(208, 501));
					}
					catch (InterruptedException e)
					{
						e.printStackTrace();
					}

					if(getWine().isEmpty()) {
						log.info("We're out of wine");
						run = false;
					} else {
						log.info("lets eat: " + getWine().get(0).getCanvasBounds().toString());
						extUtils.moveClick(getWine().get(0).getCanvasBounds());
					}
					try
					{
						Thread.sleep(extUtils.getRandomIntBetweenRange(600, 1500));
					}
					catch (InterruptedException e)
					{
						e.printStackTrace();
					}
				}
				closestNpc = extUtils.findNearestNpc(BLACKJACK_ID);
				if (closestNpc != null) {
					npcRect = closestNpc.getConvexHull().getBounds();
					if (npcRect != null) {
						log.info("npc rectangle: " + npcRect.toString());
						extUtils.moveClick(closestNpc.getConvexHull().getBounds());
					}
				}
			} else {
				return;
			}
		}
	}

	@Subscribe
	private void onChatMessage(ChatMessage event)
	{
		final String msg = event.getMessage();

		if (event.getType() == ChatMessageType.SPAM && (msg.equals(SUCCESS_BLACKJACK) || (msg.equals(FAILED_BLACKJACK) && config.pickpocketOnAggro())))
		{
			menuManager.removePriorityEntry(KNOCKOUT_BANDIT);
			menuManager.removePriorityEntry(KNOCKOUT_MENAPHITE);
			menuManager.addPriorityEntry(PICKPOCKET_BANDIT);
			menuManager.addPriorityEntry(PICKPOCKET_MENAPHITE);
			final int ticks = config.random() ? RandomUtils.nextInt(3, 4) : 4;
			nextKnockOutTick = client.getTickCount() + ticks;
		}
	}

	private static class BJComparableEntry extends AbstractComparableEntry
	{
		private BJComparableEntry(final String npc, final boolean pickpocket)
		{
			if (!BANDIT.equals(npc) && !MENAPHITE.equals(npc))
			{
				throw new IllegalArgumentException("Only bandits or menaphites are valid");
			}

			this.setTarget(npc.toLowerCase());
			this.setOption(pickpocket ? PICKPOCKET : KNOCK_OUT);
			this.setPriority(100);
		}

		@Override
		public boolean matches(MenuEntry entry)
		{
			return entry.getOption().equalsIgnoreCase(this.getOption()) &&
				Text.removeTags(entry.getTarget(), true).equalsIgnoreCase(this.getTarget());
		}
	}

	private boolean checkHitpoints()
	{
		return client.getBoostedSkillLevel(Skill.HITPOINTS) <= config.hpThreshold();
	}

	private List<WidgetItem> getWine()
	{
		return extUtils.getItems(1993);
	}
}