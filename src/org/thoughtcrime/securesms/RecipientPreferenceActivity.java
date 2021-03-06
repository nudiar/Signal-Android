package org.thoughtcrime.securesms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v7.app.AlertDialog;
import android.support.v7.preference.CheckBoxPreference;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceCategory;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.color.MaterialColors;
import org.thoughtcrime.securesms.components.ThreadPhotoRailView;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhotoFactory;
import org.thoughtcrime.securesms.crypto.IdentityKeyParcelable;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.IdentityDatabase.IdentityRecord;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.database.loaders.ThreadMediaLoader;
import org.thoughtcrime.securesms.jobs.MultiDeviceBlockedUpdateJob;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.preferences.widgets.AdvancedRingtonePreference;
import org.thoughtcrime.securesms.preferences.widgets.ColorPickerPreference;
import org.thoughtcrime.securesms.preferences.CorrectedPreferenceFragment;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientModifiedListener;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.concurrent.ExecutionException;

public class RecipientPreferenceActivity extends PassphraseRequiredActionBarActivity implements RecipientModifiedListener, LoaderManager.LoaderCallbacks<Cursor>
{
  private static final String TAG = RecipientPreferenceActivity.class.getSimpleName();

  public static final String ADDRESS_EXTRA                = "recipient_address";
  public static final String CAN_HAVE_SAFETY_NUMBER_EXTRA = "can_have_safety_number";

  private static final String PREFERENCE_MUTED    = "pref_key_recipient_mute";
  private static final String PREFERENCE_TONE     = "pref_key_recipient_ringtone";
  private static final String PREFERENCE_VIBRATE  = "pref_key_recipient_vibrate";
  private static final String PREFERENCE_BLOCK    = "pref_key_recipient_block";
  private static final String PREFERENCE_COLOR    = "pref_key_recipient_color";
  private static final String PREFERENCE_IDENTITY = "pref_key_recipient_identity";

  private final DynamicTheme    dynamicTheme    = new DynamicNoActionBarTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private ImageView               avatar;
  private MasterSecret            masterSecret;
  private Address                 address;
  private TextView                threadPhotoRailLabel;
  private ThreadPhotoRailView     threadPhotoRailView;
  private CollapsingToolbarLayout toolbarLayout;
  private BroadcastReceiver       staleReceiver;

  @Override
  public void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  public void onCreate(Bundle instanceState, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.recipient_preference_activity);
    this.masterSecret = masterSecret;
    this.address      = getIntent().getParcelableExtra(ADDRESS_EXTRA);

    Recipient recipient = Recipient.from(this, address, true);

    initializeToolbar();
    initializeReceivers();
    setHeader(recipient);
    recipient.addListener(this);

    getSupportLoaderManager().initLoader(0, null, this);
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    unregisterReceiver(staleReceiver);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.preference_fragment);
    fragment.onActivityResult(requestCode, resultCode, data);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
      case android.R.id.home:
        onBackPressed();
        return true;
    }

    return false;
  }

  @Override
  public void onBackPressed() {
    finish();
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
  }

  private void initializeToolbar() {
    this.toolbarLayout        = ViewUtil.findById(this, R.id.collapsing_toolbar);
    this.avatar               = ViewUtil.findById(this, R.id.avatar);
    this.threadPhotoRailView  = ViewUtil.findById(this, R.id.recent_photos);
    this.threadPhotoRailLabel = ViewUtil.findById(this, R.id.rail_label);

    this.toolbarLayout.setExpandedTitleColor(getResources().getColor(R.color.white));
    this.toolbarLayout.setCollapsedTitleTextColor(getResources().getColor(R.color.white));

    this.threadPhotoRailView.setListener(mediaRecord -> {
      Intent intent = new Intent(RecipientPreferenceActivity.this, MediaPreviewActivity.class);
      intent.putExtra(MediaPreviewActivity.ADDRESS_EXTRA, address);
      intent.putExtra(MediaPreviewActivity.OUTGOING_EXTRA, mediaRecord.isOutgoing());
      intent.putExtra(MediaPreviewActivity.DATE_EXTRA, mediaRecord.getDate());
      intent.putExtra(MediaPreviewActivity.SIZE_EXTRA, mediaRecord.getAttachment().getSize());
      intent.setDataAndType(mediaRecord.getAttachment().getDataUri(), mediaRecord.getContentType());
      startActivity(intent);
    });

    this.threadPhotoRailLabel.setOnClickListener(v -> {
      Intent intent = new Intent(this, MediaOverviewActivity.class);
      intent.putExtra(MediaOverviewActivity.ADDRESS_EXTRA, address);
      startActivity(intent);
    });

    Toolbar toolbar = ViewUtil.findById(this, R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setLogo(null);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
      getWindow().setStatusBarColor(Color.TRANSPARENT);
    }
  }

  private void initializeReceivers() {
    this.staleReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Recipient recipient = Recipient.from(context, (Address)getIntent().getParcelableExtra(ADDRESS_EXTRA), true);
        recipient.addListener(RecipientPreferenceActivity.this);
        onModified(recipient);
      }
    };

    IntentFilter staleFilter = new IntentFilter();
    staleFilter.addAction(GroupDatabase.DATABASE_UPDATE_ACTION);
    staleFilter.addAction(Recipient.RECIPIENT_CLEAR_ACTION);

    registerReceiver(staleReceiver, staleFilter);
  }

  private void setHeader(@NonNull Recipient recipient) {
    new AsyncTask<Void, Void, ContactPhoto>() {
      @Override
      protected @NonNull ContactPhoto doInBackground(Void... params) {
        DisplayMetrics metrics       = new DisplayMetrics();
        WindowManager  windowManager = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        Uri            contentUri    = ContactsContract.Contacts.lookupContact(getContentResolver(), recipient.getContactUri());
        windowManager.getDefaultDisplay().getMetrics(metrics);

        if (recipient.isGroupRecipient()) {
          Optional<GroupDatabase.GroupRecord> groupRecord = DatabaseFactory.getGroupDatabase(RecipientPreferenceActivity.this).getGroup(recipient.getAddress().toGroupString());

          if (groupRecord.isPresent() && groupRecord.get().getAvatar() != null) {
            return ContactPhotoFactory.getGroupContactPhoto(groupRecord.get().getAvatar());
          } else {
            return ContactPhotoFactory.getDefaultGroupPhoto();
          }
        } else {
          return ContactPhotoFactory.getContactPhoto(RecipientPreferenceActivity.this, contentUri,
                                                     recipient.getAddress(), recipient.getName(),
                                                     metrics.widthPixels);
        }
      }

      protected void onPostExecute(@NonNull ContactPhoto contactPhoto) {
        if (contactPhoto.isGenerated() || contactPhoto.isResource()) avatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        else                                                         avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);

        avatar.setImageDrawable(contactPhoto.asCallCard(RecipientPreferenceActivity.this));
        avatar.setBackgroundColor(recipient.getColor().toActionBarColor(RecipientPreferenceActivity.this));
        toolbarLayout.setTitle(recipient.toShortString());
        toolbarLayout.setContentScrimColor(recipient.getColor().toActionBarColor(RecipientPreferenceActivity.this));
      }
    }.execute();
  }

  @Override
  public void onModified(final Recipient recipient) {
    Util.runOnMain(() -> setHeader(recipient));
  }

  @Override
  public Loader<Cursor> onCreateLoader(int id, Bundle args) {
    return new ThreadMediaLoader(this, masterSecret, address, true);
  }

  @Override
  public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
    if (data != null && data.getCount() > 0) {
      this.threadPhotoRailLabel.setVisibility(View.VISIBLE);
      this.threadPhotoRailView.setVisibility(View.VISIBLE);
    } else {
      this.threadPhotoRailLabel.setVisibility(View.GONE);
      this.threadPhotoRailView.setVisibility(View.GONE);
    }

    this.threadPhotoRailView.setCursor(data, masterSecret);

    Bundle bundle = new Bundle();
    bundle.putParcelable(ADDRESS_EXTRA, address);
    initFragment(R.id.preference_fragment, new RecipientPreferenceFragment(), masterSecret, null, bundle);
  }

  @Override
  public void onLoaderReset(Loader<Cursor> loader) {
    this.threadPhotoRailView.setCursor(null, masterSecret);
  }

  public static class RecipientPreferenceFragment
      extends    CorrectedPreferenceFragment
      implements RecipientModifiedListener
  {
    private Recipient         recipient;
    private BroadcastReceiver staleReceiver;
    private boolean           canHaveSafetyNumber;

    @Override
    public void onCreate(Bundle icicle) {
      Log.w(TAG, "onCreate (fragment)");
      super.onCreate(icicle);

      initializeRecipients();

      this.canHaveSafetyNumber = getActivity().getIntent()
                                 .getBooleanExtra(RecipientPreferenceActivity.CAN_HAVE_SAFETY_NUMBER_EXTRA, false);

      this.findPreference(PREFERENCE_TONE)
          .setOnPreferenceChangeListener(new RingtoneChangeListener());
      this.findPreference(PREFERENCE_VIBRATE)
          .setOnPreferenceChangeListener(new VibrateChangeListener());
      this.findPreference(PREFERENCE_MUTED)
          .setOnPreferenceClickListener(new MuteClickedListener());
      this.findPreference(PREFERENCE_BLOCK)
          .setOnPreferenceClickListener(new BlockClickedListener());
      this.findPreference(PREFERENCE_COLOR)
          .setOnPreferenceChangeListener(new ColorChangeListener());
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
      Log.w(TAG, "onCreatePreferences...");
      addPreferencesFromResource(R.xml.recipient_preferences);
    }

    @Override
    public void onResume() {
      super.onResume();
      setSummaries(recipient);
    }

    @Override
    public void onDestroy() {
      super.onDestroy();
      this.recipient.removeListener(this);
      getActivity().unregisterReceiver(staleReceiver);
    }

    private void initializeRecipients() {
      this.recipient = Recipient.from(getActivity(),
                                      (Address)getArguments().getParcelable(ADDRESS_EXTRA),
                                      true);

      this.recipient.addListener(this);

      this.staleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          recipient.removeListener(RecipientPreferenceFragment.this);
          recipient = Recipient.from(getActivity(), (Address)getArguments().getParcelable(ADDRESS_EXTRA), true);
          onModified(recipient);
        }
      };

      IntentFilter intentFilter = new IntentFilter();
      intentFilter.addAction(GroupDatabase.DATABASE_UPDATE_ACTION);
      intentFilter.addAction(Recipient.RECIPIENT_CLEAR_ACTION);

      getActivity().registerReceiver(staleReceiver, intentFilter);
    }

    private void setSummaries(Recipient recipient) {
      CheckBoxPreference         mutePreference     = (CheckBoxPreference) this.findPreference(PREFERENCE_MUTED);
      AdvancedRingtonePreference ringtonePreference = (AdvancedRingtonePreference) this.findPreference(PREFERENCE_TONE);
      ListPreference             vibratePreference  = (ListPreference) this.findPreference(PREFERENCE_VIBRATE);
      ColorPickerPreference      colorPreference    = (ColorPickerPreference) this.findPreference(PREFERENCE_COLOR);
      Preference                 blockPreference    = this.findPreference(PREFERENCE_BLOCK);
      Preference                 identityPreference = this.findPreference(PREFERENCE_IDENTITY);
      PreferenceCategory         privacyCategory    = (PreferenceCategory)this.findPreference("privacy_settings");
      PreferenceCategory         divider            = (PreferenceCategory)this.findPreference("divider");

      mutePreference.setChecked(recipient.isMuted());

      final Uri toneUri = recipient.getRingtone();

      if (toneUri == null) {
        ringtonePreference.setSummary(R.string.preferences__default);
        ringtonePreference.setCurrentRingtone(Uri.parse(TextSecurePreferences.getNotificationRingtone(getContext())));
      } else if (toneUri.toString().isEmpty()) {
        ringtonePreference.setSummary(R.string.preferences__silent);
        ringtonePreference.setCurrentRingtone(null);
      } else {
        Ringtone tone = RingtoneManager.getRingtone(getActivity(), toneUri);

        if (tone != null) {
          ringtonePreference.setSummary(tone.getTitle(getActivity()));
          ringtonePreference.setCurrentRingtone(toneUri);
        }
      }

      if (recipient.getVibrate() == VibrateState.DEFAULT) {
        vibratePreference.setSummary(R.string.preferences__default);
        vibratePreference.setValueIndex(0);
      } else if (recipient.getVibrate() == VibrateState.ENABLED) {
        vibratePreference.setSummary(R.string.RecipientPreferenceActivity_enabled);
        vibratePreference.setValueIndex(1);
      } else {
        vibratePreference.setSummary(R.string.RecipientPreferenceActivity_disabled);
        vibratePreference.setValueIndex(2);
      }

      if (recipient.isGroupRecipient()) {
        if (colorPreference    != null) colorPreference.setVisible(false);
        if (blockPreference    != null) blockPreference.setVisible(false);
        if (identityPreference != null) identityPreference.setVisible(false);
        if (privacyCategory    != null) privacyCategory.setVisible(false);
        if (divider            != null) divider.setVisible(false);
      } else {
        colorPreference.setColors(MaterialColors.CONVERSATION_PALETTE.asConversationColorArray(getActivity()));
        colorPreference.setColor(recipient.getColor().toActionBarColor(getActivity()));

        if (recipient.isBlocked()) blockPreference.setTitle(R.string.RecipientPreferenceActivity_unblock);
        else                       blockPreference.setTitle(R.string.RecipientPreferenceActivity_block);

        IdentityUtil.getRemoteIdentityKey(getActivity(), recipient).addListener(new ListenableFuture.Listener<Optional<IdentityRecord>>() {
          @Override
          public void onSuccess(Optional<IdentityRecord> result) {
            if (result.isPresent()) {
              if (identityPreference != null) identityPreference.setOnPreferenceClickListener(new IdentityClickedListener(result.get()));
              if (identityPreference != null) identityPreference.setEnabled(true);
            } else if (canHaveSafetyNumber) {
              if (identityPreference != null) identityPreference.setSummary(R.string.RecipientPreferenceActivity_available_once_a_message_has_been_sent_or_received);
              if (identityPreference != null) identityPreference.setEnabled(false);
            } else {
              if (identityPreference != null) getPreferenceScreen().removePreference(identityPreference);
            }
          }

          @Override
          public void onFailure(ExecutionException e) {
            if (identityPreference != null) getPreferenceScreen().removePreference(identityPreference);
          }
        });
      }
    }

    @Override
    public void onModified(final Recipient recipient) {
      Util.runOnMain(() -> setSummaries(recipient));
    }

    private class RingtoneChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        Uri value = (Uri)newValue;

        if (Settings.System.DEFAULT_NOTIFICATION_URI.equals(value)) {
          value = null;
        }

        new AsyncTask<Uri, Void, Void>() {
          @Override
          protected Void doInBackground(Uri... params) {
            DatabaseFactory.getRecipientDatabase(getActivity())
                           .setRingtone(recipient, params[0]);
            return null;
          }
        }.execute(value);

        return false;
      }
    }

    private class VibrateChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
              int          value        = Integer.parseInt((String) newValue);
        final VibrateState vibrateState = VibrateState.fromId(value);

        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            DatabaseFactory.getRecipientDatabase(getActivity())
                           .setVibrate(recipient, vibrateState);
            return null;
          }
        }.execute();

        return false;
      }
    }

    private class ColorChangeListener implements Preference.OnPreferenceChangeListener {

      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        final int           value         = (Integer) newValue;
        final MaterialColor selectedColor = MaterialColors.CONVERSATION_PALETTE.getByColor(getActivity(), value);
        final MaterialColor currentColor  = recipient.getColor();

        if (selectedColor == null) return true;

        if (preference.isEnabled() && !currentColor.equals(selectedColor)) {
          new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
              Context context = getActivity();
              DatabaseFactory.getRecipientDatabase(context).setColor(recipient, selectedColor);

              if (recipient.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED) {
                ApplicationContext.getInstance(context)
                                  .getJobManager()
                                  .add(new MultiDeviceContactUpdateJob(context, recipient.getAddress()));
              }
              return null;
            }
          }.execute();
        }
        return true;
      }
    }

    private class MuteClickedListener implements Preference.OnPreferenceClickListener {
      @Override
      public boolean onPreferenceClick(Preference preference) {
        if (recipient.isMuted()) handleUnmute();
        else                     handleMute();

        return true;
      }

      private void handleMute() {
        MuteDialog.show(getActivity(), new MuteDialog.MuteSelectionListener() {
          @Override
          public void onMuted(long until) {
            setMuted(recipient, until);
          }
        });

        setSummaries(recipient);
      }

      private void handleUnmute() {
        setMuted(recipient, 0);
      }

      private void setMuted(final Recipient recipient, final long until) {
        recipient.setMuted(until);

        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            DatabaseFactory.getRecipientDatabase(getActivity())
                           .setMuted(recipient, until);
            return null;
          }
        }.execute();
      }
    }

    private class IdentityClickedListener implements Preference.OnPreferenceClickListener {

      private final IdentityRecord identityKey;

      private IdentityClickedListener(IdentityRecord identityKey) {
        Log.w(TAG, "Identity record: " + identityKey);
        this.identityKey = identityKey;
      }

      @Override
      public boolean onPreferenceClick(Preference preference) {
        Intent verifyIdentityIntent = new Intent(getActivity(), VerifyIdentityActivity.class);
        verifyIdentityIntent.putExtra(VerifyIdentityActivity.ADDRESS_EXTRA, recipient.getAddress());
        verifyIdentityIntent.putExtra(VerifyIdentityActivity.IDENTITY_EXTRA, new IdentityKeyParcelable(identityKey.getIdentityKey()));
        verifyIdentityIntent.putExtra(VerifyIdentityActivity.VERIFIED_EXTRA, identityKey.getVerifiedStatus() == IdentityDatabase.VerifiedStatus.VERIFIED);
        startActivity(verifyIdentityIntent);

        return true;
      }
    }

    private class BlockClickedListener implements Preference.OnPreferenceClickListener {
      @Override
      public boolean onPreferenceClick(Preference preference) {
        if (recipient.isBlocked()) handleUnblock();
        else                       handleBlock();

        return true;
      }

      private void handleBlock() {
        new AlertDialog.Builder(getActivity())
            .setTitle(R.string.RecipientPreferenceActivity_block_this_contact_question)
            .setMessage(R.string.RecipientPreferenceActivity_you_will_no_longer_receive_messages_and_calls_from_this_contact)
            .setCancelable(true)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.RecipientPreferenceActivity_block, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                setBlocked(recipient, true);
              }
            }).show();
      }

      private void handleUnblock() {
        new AlertDialog.Builder(getActivity())
            .setTitle(R.string.RecipientPreferenceActivity_unblock_this_contact_question)
            .setMessage(R.string.RecipientPreferenceActivity_you_will_once_again_be_able_to_receive_messages_and_calls_from_this_contact)
            .setCancelable(true)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.RecipientPreferenceActivity_unblock, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                setBlocked(recipient, false);
              }
            }).show();
      }

      private void setBlocked(final Recipient recipient, final boolean blocked) {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            Context context = getActivity();

            DatabaseFactory.getRecipientDatabase(context)
                           .setBlocked(recipient, blocked);

            ApplicationContext.getInstance(context)
                              .getJobManager()
                              .add(new MultiDeviceBlockedUpdateJob(context));
            return null;
          }
        }.execute();
      }
    }
  }
}
