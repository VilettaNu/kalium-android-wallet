package com.banano.kaliumwallet.ui.send;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import com.banano.kaliumwallet.R;
import com.banano.kaliumwallet.bus.ContactSelected;
import com.banano.kaliumwallet.bus.RxBus;
import com.banano.kaliumwallet.databinding.FragmentSendBinding;
import com.banano.kaliumwallet.model.Address;
import com.banano.kaliumwallet.model.Contact;
import com.banano.kaliumwallet.model.Credentials;
import com.banano.kaliumwallet.model.KaliumWallet;
import com.banano.kaliumwallet.network.AccountService;
import com.banano.kaliumwallet.ui.common.ActivityWithComponent;
import com.banano.kaliumwallet.ui.common.BaseDialogFragment;
import com.banano.kaliumwallet.ui.common.DigitsInputFilter;
import com.banano.kaliumwallet.ui.common.SwipeDismissTouchListener;
import com.banano.kaliumwallet.ui.common.UIUtil;
import com.banano.kaliumwallet.ui.common.WindowControl;
import com.banano.kaliumwallet.ui.contact.ContactSelectionAdapter;
import com.banano.kaliumwallet.ui.scan.ScanActivity;
import com.banano.kaliumwallet.util.NumberUtil;
import com.banano.kaliumwallet.util.SharedPreferencesUtil;
import com.hwangjr.rxbus.annotation.Subscribe;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import io.realm.Case;
import io.realm.Realm;
import io.realm.RealmQuery;

import static android.app.Activity.RESULT_OK;
import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

/**
 * Send main screen
 */
public class SendDialogFragment extends BaseDialogFragment {
    public static String TAG = SendDialogFragment.class.getSimpleName();
    @Inject
    KaliumWallet wallet;
    @Inject
    AccountService accountService;
    @Inject
    SharedPreferencesUtil sharedPreferencesUtil;
    @Inject
    Realm realm;
    private FragmentSendBinding binding;
    private Address address;
    private Activity mActivity;
    private ContactSelectionAdapter mAdapter;

    /**
     * Create new instance of the dialog fragment (handy pattern if any data needs to be passed to it)
     *
     * @return SendDialogFragment instance
     */
    public static SendDialogFragment newInstance(String contactName) {
        Bundle args = new Bundle();
        args.putString("contact_name", contactName);
        SendDialogFragment fragment = new SendDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_FRAME, R.style.AppTheme_Modal_Window);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // init dependency injection
        mActivity = getActivity();
        if (mActivity instanceof ActivityWithComponent) {
            ((ActivityWithComponent) mActivity).getActivityComponent().inject(this);
        }

        // get data
        Credentials credentials = realm.where(Credentials.class).findFirst();
        if (credentials != null) {
            address = new Address(credentials.getAddressString());
        }

        // inflate the view
        binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_send, container, false);
        view = binding.getRoot();
        binding.setHandlers(new ClickHandlers());

        // subscribe to bus
        RxBus.get().register(this);

        // Set balance hint
        binding.sendBalance.setText(getString(R.string.send_balance, wallet.getAccountBalanceBanano()));

        // Restrict height
        Window window = getDialog().getWindow();
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, UIUtil.getDialogHeight(false, getContext()));
        window.setGravity(Gravity.BOTTOM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

        // Shadow
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams windowParams = window.getAttributes();
        windowParams.dimAmount = 0.60f;
        windowParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        window.setAttributes(windowParams);

        // Swipe down to dismiss
        getDialog().getWindow().getDecorView().setOnTouchListener(new SwipeDismissTouchListener(getDialog().getWindow().getDecorView(),
                null, new SwipeDismissTouchListener.DismissCallbacks() {
            @Override
            public boolean canDismiss(Object token) {
                return true;
            }

            @Override
            public void onDismiss(View view, Object token) {
                dismiss();
            }

            @Override
            public void onTap(View view) {
                binding.sendContainer.requestFocus();
                hideKeyboard(view);
            }
        }, SwipeDismissTouchListener.TOP_TO_BOTTOM));

        // colorize address text
        if (binding != null &&
                binding.sendFromAddress != null &&
                address != null &&
                address.getAddress() != null) {
            binding.sendFromAddress.setText(UIUtil.getColorizedSpannable(address.getAddress(), getContext()));
        }


        // Set color on destination  when valid
        binding.sendAddress.addTextChangedListener(new TextWatcher() {
            String lastText = "";
            boolean toContact = false;
            boolean isColorized = false;
            boolean fromColorization = false;

            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                String curText = binding.sendAddress.getText().toString().trim();
                if (curText.startsWith("@")) {
                    if (toContact) {
                        toContact = false;
                        updateContactSearch();
                        binding.sendAddress.clearFocus();
                        return;
                    }
                    binding.contactRecyclerview.setVisibility(View.VISIBLE);
                    binding.sendAddress.setGravity(Gravity.START);
                    binding.sendAddress.setBackground(getResources().getDrawable(R.drawable.bg_edittext_bottom_round));
                    updateContactSearch();
                    return;
                } else {
                    if (!fromColorization) {
                        binding.sendAddress.setTextColor(getResources().getColor(R.color.white_60));
                    }
                    binding.contactRecyclerview.setVisibility(View.GONE);
                    binding.sendAddress.setGravity(Gravity.CENTER);
                    binding.sendAddress.setBackground(getResources().getDrawable(R.drawable.bg_edittext));
                }
                if (curText.equals(lastText)) {
                    if (fromColorization) {
                        fromColorization = false;
                    } else {
                        Address address = new Address(curText);
                        if (address.isValidAddress()) {
                            hideAddressError();
                            isColorized = true;
                            fromColorization = true;
                            binding.sendAddress.setText(UIUtil.getColorizedSpannableBrightWhite(address.getAddress(), getContext()));
                            binding.sendAddress.setSelection(address.getAddress().length());
                        }
                    }
                    return;
                } else if (curText.length() > 0 && lastText.length() == 0) {
                    Typeface tf = Typeface.createFromAsset(getContext().getAssets(), "font/overpass_mono_light.ttf");
                    binding.sendAddress.setPadding(binding.sendAddress.getPaddingLeft(), binding.sendAddress.getPaddingTop(), (int) UIUtil.convertDpToPixel(55, getContext()), binding.sendAddress.getPaddingBottom());
                    binding.sendAddress.setTypeface(tf);
                } else if (curText.length() == 0 && lastText.length() > 0) {
                    Typeface tf = Typeface.createFromAsset(getContext().getAssets(), "font/nunitosans_extralight.ttf");
                    binding.sendAddress.setPadding(binding.sendAddress.getPaddingLeft(), binding.sendAddress.getPaddingTop(), (int) UIUtil.convertDpToPixel(20, getContext()), binding.sendAddress.getPaddingBottom());
                    binding.sendAddress.setTypeface(tf);
                }
                if (!curText.equals(lastText)) {
                    lastText = curText;
                    Address address = new Address(curText);
                    if (address.isValidAddress()) {
                        hideAddressError();
                        Contact c = realm.where(Contact.class).equalTo("address", address.getAddress()).findFirst();
                        if (c != null) {
                            lastText = c.getName();
                            toContact = true;
                            binding.sendAddress.setText(c.getName());
                        } else {
                            isColorized = true;
                            fromColorization = true;
                            binding.sendAddress.setText(UIUtil.getColorizedSpannableBrightWhite(address.getAddress(), getContext()));
                            binding.sendAddress.setSelection(address.getAddress().length());
                        }
                    } else {
                        if (isColorized) {
                            fromColorization = false;
                            binding.sendAddress.setText(new SpannableString(curText));
                            binding.sendAddress.setSelection(curText.length());
                            isColorized = false;
                        }
                    }
                }
            }

        });

        // Amount validation
        binding.sendAmount.setFilters(new InputFilter[]{new DigitsInputFilter(Integer.MAX_VALUE, 2, Integer.MAX_VALUE)});
        binding.sendAmount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable e) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence.toString().length() > 0) {
                    Typeface tf = Typeface.createFromAsset(getContext().getAssets(), "font/nunitosans_bold.ttf");
                    binding.sendAmount.setTypeface(tf);
                } else {
                    Typeface tf = Typeface.createFromAsset(getContext().getAssets(), "font/nunitosans_extralight.ttf");
                    binding.sendAmount.setTypeface(tf);
                }
                wallet.setSendBananoAmount(charSequence.toString().trim());
                binding.setWallet(wallet);
                hideAmountError();
            }
        });

        // Hide keyboard in amount field when return is pushed
        binding.sendAddress.setImeOptions(EditorInfo.IME_ACTION_DONE);
        binding.sendAddress.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        // Remove hint when focused
        binding.sendAmount.setOnFocusChangeListener((View view, boolean isFocused) -> {
            if (isFocused) {
                binding.sendAmount.setHint("");
            } else {
                binding.sendAmount.setHint(R.string.send_amount_hint);
            }
        });
        binding.sendAddress.setOnFocusChangeListener((View view, boolean isFocused) -> {
            if (isFocused) {
                binding.sendAddress.setHint("");
                if (binding.sendAddress.getText().toString().startsWith("@")) {
                    binding.sendAddress.setGravity(Gravity.START);
                }
            } else {
                binding.sendAddress.setGravity(Gravity.CENTER);
                binding.contactRecyclerview.setVisibility(View.GONE);
                binding.sendAddress.setBackground(getResources().getDrawable(R.drawable.bg_edittext));
                binding.sendAddress.setHint(R.string.send_address_hint);
                if (binding.sendAddress.getText().toString().startsWith("@")) {
                    RealmQuery realmQuery = realm.where(Contact.class);
                    realmQuery.equalTo("name", binding.sendAddress.getText().toString());
                    if (realmQuery.count() == 0) {
                        binding.sendAddress.setText("");
                    }
                }
            }
        });

        // Prepare contacts info
        List<Contact> contacts = realm.where(Contact.class).findAll().sort("name");
        binding.contactRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new ContactSelectionAdapter(contacts);
        binding.contactRecyclerview.setItemAnimator(null);
        binding.contactRecyclerview.setAdapter(mAdapter);

        // Prefill address if applicable
        String contactName = getArguments().getString("contact_name", null);
        if (contactName != null) {
            binding.sendAddress.requestFocus();
            binding.sendAddress.setText(contactName);
            binding.sendAddress.clearFocus();
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // unregister from bus
        RxBus.get().unregister(this);
    }

    @Subscribe
    public void receiveContactSelected(ContactSelected contactSelected) {
        binding.sendAddress.setText(contactSelected.getName());
        hideKeyboard(binding.sendAddress);
        binding.sendContainer.requestFocus();
    }

    private void updateContactSearch() {
        String searchTerm = binding.sendAddress.getText().toString();
        if (!searchTerm.startsWith("@")) {
            return;
        }
        searchTerm = searchTerm.substring(1, searchTerm.length());
        List<Contact> contacts = realm.where(Contact.class).contains("name", searchTerm, Case.INSENSITIVE).findAll().sort("name");
        mAdapter.updateList(contacts);
        // Colorize name if a valid contact
        if (contacts.size() > 0) {
            String name = binding.sendAddress.getText().toString().trim();
            boolean foundMatch = false;
            for (Contact c : contacts) {
                if (c.getName().equals(name)) {
                    binding.sendAddress.setTextColor(getResources().getColor(R.color.yellow));
                    foundMatch = true;
                    break;
                }
            }
            if (!foundMatch) {
                binding.sendAddress.setTextColor(getResources().getColor(R.color.white_60));
            }
        } else {
            binding.sendAddress.setTextColor(getResources().getColor(R.color.white_60));
        }
    }

    private void showAddressError(int str_id) {
        binding.sendAddressValidation.setVisibility(View.VISIBLE);
        binding.sendAddressValidation.setText(getString(str_id));
    }

    private void hideAddressError() {
        binding.sendAddressValidation.setVisibility(View.INVISIBLE);
    }

    private boolean validateAddress() {
        String address = binding.sendAddress.getText().toString().trim();
        if (address.startsWith("@")) {
            Contact c = realm.where(Contact.class).equalTo("name", address).findFirst();
            if (c != null) {
                address = c.getAddress();
            } else {
                showAddressError(R.string.contact_invalid_name);
                return false;
            }
        }
        // check for valid address
        if (address.isEmpty()) {
            showAddressError(R.string.send_enter_address);
            return false;
        }
        Address destination = new Address(address);
        if (!destination.isValidAddress()) {
            showAddressError(R.string.send_invalid_address);
            return false;
        }
        hideAddressError();
        return true;
    }

    private void showAmountError(int str_id) {
        binding.sendAmountValidation.setVisibility(View.VISIBLE);
        binding.sendAmountValidation.setText(getString(str_id));
    }

    private void hideAmountError() {
        binding.sendAmountValidation.setVisibility(View.INVISIBLE);
    }

    private boolean validateAmount() {
        BigInteger sendAmount = NumberUtil.getAmountAsRawBigInteger(wallet.getSendBananoAmount());
        // check that amount being sent is less than or equal to account balance
        if (wallet.getSendBananoAmount().isEmpty()) {
            showAmountError(R.string.send_enter_amount);
            return false;
        } else if (sendAmount.compareTo(new BigInteger("0")) <= -1 || sendAmount.compareTo(new BigInteger("0")) == 0) {
            showAmountError(R.string.send_amount_error);
            return false;
        }
        if (sendAmount.compareTo(wallet.getAccountBalanceBananoRaw().toBigInteger()) > 0) {
            showAmountError(R.string.send_insufficient_balance);
            return false;
        }

        // check that we have a frontier block
        if (wallet.getFrontierBlock() == null) {
            showAmountError(R.string.send_no_frontier);
            return false;
        }
        hideAmountError();
        return true;
    }

    private boolean validateRequest() {
        boolean amountValid = validateAmount();
        boolean addressValid = validateAddress();
        if (!amountValid || !addressValid) {
            return false;
        } else {
            hideAmountError();
            hideAddressError();
            return true;
        }
    }

    private void showSendCompleteDialog() {
        // show complete dialog
        SendCompleteDialogFragment dialog = SendCompleteDialogFragment.newInstance(binding.sendAddress.getText().toString(), binding.sendAmount.getText().toString());
        dialog.show(((WindowControl) mActivity).getFragmentUtility().getFragmentManager(),
                SendCompleteDialogFragment.TAG);
        executePendingTransactions();
    }

    private void showSendConfirmDialog() {
        // show send dialog
        SendConfirmDialogFragment dialog = SendConfirmDialogFragment.newInstance(binding.sendAddress.getText().toString(), binding.sendAmount.getText().toString());
        dialog.setTargetFragment(this, SEND_RESULT);
        dialog.show(((WindowControl) mActivity).getFragmentUtility().getFragmentManager(),
                SendConfirmDialogFragment.TAG);
        executePendingTransactions();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SEND_RESULT) {
            if (resultCode == SEND_COMPLETE) {
                showSendCompleteDialog();
                dismiss();
            } else if (resultCode == SEND_FAILED) {
                UIUtil.showToast(getString(R.string.send_generic_error), getContext());
            } else if (resultCode == SEND_FAILED_AMOUNT) {
                wallet.setSendBananoAmount(wallet.getUsableAccountBalanceBanano().toString());
                binding.setWallet(wallet);
                showAmountError(R.string.send_amount_error);
            }
        } else if (requestCode == SCAN_RESULT) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                Bundle res = data.getExtras();
                if (res != null) {
                    // parse address
                    Address address = new Address(res.getString(ScanActivity.QR_CODE_RESULT));

                    // set to scanned value
                    if (address.getAddress() != null) {
                        binding.sendAddress.setText(address.getAddress());
                    }

                    if (address.getAmount() != null) {
                        wallet.setSendBananoAmount(address.getAmount());
                        binding.setWallet(wallet);
                    }
                }
            }
        }
    }

    /**
     * Execute all pending transactions
     */
    private void executePendingTransactions() {
        ((WindowControl) getActivity()).getFragmentUtility().getFragmentManager().executePendingTransactions();
    }

    public class ClickHandlers {
        public void onClickClose(View view) {
            dismiss();
        }

        public void onClickQR(View view) {
            startScanActivity(getString(R.string.scan_send_instruction_label), false);
        }

        public void onClickSend(View view) {
            if (!validateRequest()) {
                return;
            } else if (mActivity instanceof WindowControl) {
                // show send dialog
                showSendConfirmDialog();
            }
        }

        public void onClickMax(View view) {
            binding.sendAmount.setText(String.format(Locale.ENGLISH, "%.2f", wallet.getUsableAccountBalanceBanano().floatValue()));
        }

        public void onClickPaste(View view) {
            // copy address to clipboard
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClipDescription().hasMimeType(MIMETYPE_TEXT_PLAIN)) {
                Address address = new Address(clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                binding.sendAddress.setText(address.getAddress());
            }
        }
    }
}
