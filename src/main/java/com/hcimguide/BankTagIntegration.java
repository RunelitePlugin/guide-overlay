package com.hcimguide;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration with RuneLite's built-in Bank Tags plugin: the active bank
 * section's withdraw items are kept under a managed tag, and opening the bank
 * automatically opens that tag's tab - so the trip's items are in front of
 * you instead of scattered through your bank. When the section completes,
 * the tag is rebuilt for the next section automatically.
 *
 * Boundaries, deliberately: the plugin only FILTERS the bank view - you still
 * click and withdraw items yourself (automating withdrawals would break
 * Jagex's rules). Items whose names don't resolve to a real item id
 * ("2x Food") can't be tagged and stay manual, and the built-in Bank Tags
 * plugin must be enabled for the tab to appear. The managed tag is fully
 * removed when the feature is toggled off or the plugin shuts down.
 */
@Singleton
public class BankTagIntegration
{
	private static final Logger log = LoggerFactory.getLogger(BankTagIntegration.class);

	/** Namespaced so it can never collide with the user's own tags. */
	static final String TAG = "guide-overlay";

	private final TagManager tagManager;
	private final BankTagsService bankTagsService;
	private final ItemIconResolver iconResolver;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;
	private final HcimGuideConfig config;

	/**
	 * Signature the tag currently reflects; null = cleared. Initialized to the
	 * sentinel so the very first sync request always runs - this scrubs a tag
	 * orphaned by a crashed session even when no active bank exists yet.
	 */
	private volatile String syncedBankId = "!invalidated!";
	private volatile boolean tagHasItems;

	@Inject
	public BankTagIntegration(TagManager tagManager, BankTagsService bankTagsService,
		ItemIconResolver iconResolver, ScheduledExecutorService executor, ClientThread clientThread,
		HcimGuideConfig config)
	{
		this.tagManager = tagManager;
		this.bankTagsService = bankTagsService;
		this.iconResolver = iconResolver;
		this.executor = executor;
		this.clientThread = clientThread;
		this.config = config;
	}

	/**
	 * Rebuild the managed tag for the given bank section if it changed.
	 * Cheap when nothing changed (one string compare); actual tagging runs
	 * on the executor.
	 */
	public void requestSync(String bankId, List<ItemReq> items)
	{
		if (Objects.equals(bankId, syncedBankId))
		{
			return;
		}
		syncedBankId = bankId;
		final List<ItemReq> itemsCopy = items == null ? new ArrayList<>() : new ArrayList<>(items);
		executor.execute(() -> sync(bankId, itemsCopy));
	}

	private void sync(String bankId, List<ItemReq> items)
	{
		// somebody re-requested meanwhile; let the newest request win
		if (!Objects.equals(bankId, syncedBankId))
		{
			return;
		}
		// name -> id resolution can run here on the executor...
		final Set<Integer> distinct = new LinkedHashSet<>();
		if (bankId != null && !items.isEmpty())
		{
			int[] ids = iconResolver.resolve(items);
			for (int id : ids)
			{
				if (id > 0)
				{
					distinct.add(id);
				}
			}
		}
		// ...but TagManager mutations canonicalize item ids through the client
		// (getItemComposition), which is client-thread-bound
		clientThread.invokeLater(() ->
		{
			try
			{
				if (!Objects.equals(bankId, syncedBankId))
				{
					return;
				}
				tagManager.removeTag(TAG);
				for (int id : distinct)
				{
					tagManager.addTag(id, TAG, false);
				}
				tagHasItems = !distinct.isEmpty();
			}
			catch (Exception e)
			{
				// Bank Tags internals unavailable/changed - never break the guide
				// over it. Mark the signature un-synced so the next sync cycle
				// retries instead of believing the (possibly half-applied) tag
				// is correct until the bank changes.
				if (Objects.equals(bankId, syncedBankId))
				{
					syncedBankId = "!invalidated!";
					tagHasItems = false;
				}
				log.warn("Bank tag sync failed", e);
			}
		});
	}

	/**
	 * Called when the bank interface opens (client thread). Opens the managed
	 * tag's tab if it has items. Only fires on bank OPEN - if the user
	 * switches to another tab afterwards, we don't fight them.
	 */
	public void onBankOpened()
	{
		if (!tagHasItems || !config.bankTagAutoOpen())
		{
			return;
		}
		// let the built-in Bank Tag Layouts arrange the tab unless the user
		// disabled it (then force a plain grid via OPTION_NO_LAYOUT)
		final int options = config.bankTagUseLayout() ? 0 : BankTagsService.OPTION_NO_LAYOUT;
		clientThread.invokeLater(() ->
		{
			try
			{
				bankTagsService.openBankTag(TAG, options);
			}
			catch (Exception e)
			{
				log.warn("Could not open bank tag tab", e);
			}
		});
	}

	/**
	 * Remove the managed tag everywhere and close its tab if it's showing.
	 * All TagManager/BankTagsService work happens on the client thread.
	 * Safe to call unconditionally (e.g. at startup with the feature off, to
	 * scrub a tag left behind by a crashed session).
	 */
	public void cleanup()
	{
		syncedBankId = null;
		tagHasItems = false;
		clientThread.invokeLater(() ->
		{
			try
			{
				tagManager.removeTag(TAG);
				if (TAG.equals(bankTagsService.getActiveTag()))
				{
					bankTagsService.closeBankTag();
				}
			}
			catch (Exception e)
			{
				log.warn("Bank tag cleanup failed", e);
			}
		});
	}

	/** Force the next requestSync to retag even for an unchanged signature. */
	public void invalidate()
	{
		syncedBankId = "!invalidated!"; // can never equal a real signature
	}
}
