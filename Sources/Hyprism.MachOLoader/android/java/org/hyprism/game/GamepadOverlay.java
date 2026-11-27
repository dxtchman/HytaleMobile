// Copyright (C) 2026 Hyprism Launcher
// SPDX-License-Identifier: GPL-3.0-only

package org.hyprism.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * On-screen gamepad drawn over the game. It feeds an SDL virtual gamepad
 * (gamepad.c), so the client sees an ordinary controller.
 *
 * The eye button in the top bar toggles it; hold it to edit the layout. The controls only exist
 * during play: while a menu or modal is open (the game shows the cursor) and while the pad is off,
 * touches reach the game as plain mouse input. A physical gamepad turns the on-screen one off.
 */
public class GamepadOverlay extends View {
    // SDL_GamepadButton
    static final int SOUTH = 0, EAST = 1, WEST = 2, NORTH = 3, BACK = 4, START = 6,
        LEFT_STICK = 7, RIGHT_STICK = 8, LEFT_SHOULDER = 9, RIGHT_SHOULDER = 10,
        DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;
    // SDL_GamepadAxis
    static final int LEFT_X = 0, LEFT_Y = 1, RIGHT_X = 2, RIGHT_Y = 3, LEFT_TRIGGER = 4, RIGHT_TRIGGER = 5;
    static final int AXIS_MAX = 32767, AXIS_MIN = -32768;

    static native void nativeSetEnabled(boolean enabled);
    static native void nativeAxis(int axis, int value);
    static native void nativeButton(int button, boolean down);
    static native boolean nativeCaptured();

    // The hotbar slots are the keys 1 to 9. They go through SDL's own Android key path, which updates the
    // keyboard state as well as queueing the event, so a client that polls the state sees them too.

    // Where fx is measured from. Left and right edges, and the centre line, count in screen heights, so
    // the layout keeps its proportions on any aspect ratio; fy and side are fractions of the height.
    private static final int EDGE_LEFT = 1, EDGE_RIGHT = 2, EDGE_CENTER = 3;
    private static final int TILE_SHAPE = 0, OCTAGON_SHAPE = 1;

    /**
     * A touch control: a glyph (assets/icons) in a dark tile (top bar) or a chamfered octagon.
     * button >= 0 is a gamepad button; axis >= 0 is a trigger; a chord holds RIGHT_SHOULDER and then
     * presses its button, which is how Hytale's controller bindings reach the abilities. The toggle
     * control switches the pad on and off instead of sending input.
     */
    static final class Control {
        final String label, icon;
        final int edge, shape, button, axis;
        final boolean chord, toggle, tint;
        final float glyph; // longest side of the glyph as a fraction of the control side
        final float defaultFx, defaultFy, defaultSide;
        float fx, fy, side; // centre (fx from the edge, fy of the height) and side (of the height)
        Bitmap bitmap;
        boolean down;

        private Control(String label, String icon, int edge, int shape, float fx, float fy, float side,
                float glyph, int button, int axis, boolean chord, boolean toggle, boolean tint) {
            this.label = label; this.icon = icon; this.edge = edge; this.shape = shape;
            this.fx = fx; this.fy = fy; this.side = side; this.glyph = glyph;
            this.defaultFx = fx; this.defaultFy = fy; this.defaultSide = side;
            this.button = button; this.axis = axis; this.chord = chord; this.toggle = toggle; this.tint = tint;
        }
    }

    // Top bar tile, 140 px of a 1456 px tall reference frame.
    private static Control tile(String icon, float fx, float glyph, int button, boolean toggle) {
        return new Control("", icon, EDGE_LEFT, TILE_SHAPE, fx, 0.0652f, 0.0962f, glyph, button, -1, false, toggle, false);
    }

    private static Control octagon(String icon, int edge, float fx, float fy, float side, float glyph,
            int button, int axis) {
        return new Control("", icon, edge, OCTAGON_SHAPE, fx, fy, side, glyph, button, axis, false, false, false);
    }

    // Actions the reference art has no glyph for keep a Lucide icon, tinted to match at draw time.
    private static Control extra(String label, String icon, int edge, float fx, float fy, float side,
            int button, int axis, boolean chord) {
        return new Control(label, icon, edge, OCTAGON_SHAPE, fx, fy, side, 0.56f, button, axis, chord, false, true);
    }

    // Hytale's default controller bindings (Settings > Controls, build 34): A jump, R3 crouch/slide/roll,
    // L3 sprint, RT primary action, LT secondary action, X interact, Y inventory, RB + Y/X abilities,
    // LB/RB hotbar, Start menu, Back player list, B back in menus, D-pad up map, left emotes,
    // right utility item, down drop item. Positions come from a 2576 x 1456 reference frame.
    private final Control[] controls = {
        // Top bar. The eye turns the pad on and off.
        tile("hud_exit", 0.0632f, 0.74f, EAST, false),
        tile("hud_chat", 0.1703f, 0.66f, DPAD_LEFT, false),
        tile("hud_settings", 0.2802f, 0.68f, START, false),
        tile("hud_list", 0.3908f, 0.69f, BACK, false),
        tile("hud_clock", 0.5000f, 0.63f, DPAD_UP, false),
        // The eye switches the camera view (first and third person: the game's key V).
        tile("hud_eye", 0.6113f, 0.76f, -1, false),
        tile("hud_addplayer", 0.7212f, 0.74f, DPAD_RIGHT, false),
        // The gear opens the phone settings panel (GameSettingsPanel); it has no gamepad button.
        tile(GEAR, 0.8312f, 0.62f, -1, false),
        // The gamepad icon shows and hides the controls (hold it to edit the layout).
        tile(PADICON, 0.9412f, 0.62f, -1, true),
        // Left side. The stick pad is drawn by drawStick.
        octagon("hud_sprint", EDGE_LEFT, 0.1010f, 0.2273f, 0.1422f, 0.48f, LEFT_STICK, -1),
        octagon("hud_shield", EDGE_LEFT, 0.1909f, 0.3558f, 0.1422f, 0.52f, -1, LEFT_TRIGGER),
        // Right side.
        octagon("hud_jump", EDGE_RIGHT, 0.1298f, 0.2301f, 0.1490f, 0.48f, SOUTH, -1),
        octagon("hud_crouch", EDGE_RIGHT, 0.1346f, 0.3970f, 0.1477f, 0.58f, RIGHT_STICK, -1),
        octagon("hud_sword", EDGE_RIGHT, 0.2459f, 0.6566f, 0.2266f, 0.45f, -1, RIGHT_TRIGGER),
        octagon("hud_backpack", EDGE_RIGHT, 0.4217f, 0.9121f, 0.1477f, 0.45f, NORTH, -1),
        // Secondary actions.
        extra("X", "x", EDGE_RIGHT, 0.3750f, 0.4739f, 0.120f, WEST, -1, false),
        extra("RB+Y", "ab1", EDGE_RIGHT, 0.5400f, 0.2266f, 0.110f, NORTH, -1, true),
        extra("RB+X", "ab2", EDGE_RIGHT, 0.5400f, 0.3571f, 0.110f, WEST, -1, true),
        extra("LB", "lb", EDGE_CENTER, -0.4180f, 0.8344f, 0.090f, LEFT_SHOULDER, -1, false),
        extra("RB", "rb", EDGE_CENTER, 0.4180f, 0.8344f, 0.090f, RIGHT_SHOULDER, -1, false),
        extra("", "down", EDGE_RIGHT, 0.2376f, 0.0824f, 0.090f, DPAD_DOWN, -1, false),
    };

    // Colours sampled from the reference art.
    private static final int TILE_FILL = 0xE6232320, TILE_FILL_DOWN = 0xE6403E38, TILE_EDGE = 0xB8585A4E,
        OCT_FILL = 0x87141409, OCT_FILL_DOWN = 0xB3141409, OCT_EDGE = 0x59E6E1D0,
        PAD_FILL = 0x54141409, PAD_EDGE = 0x40140C00, KNOB_RING = 0xF2EDD2A2, KNOB_FILL = 0xE6D69F41,
        GLYPH = 0xFFC3CCDF, LABEL = 0xFFC3CCDF, ACCENT = 0xFFF2B33D;

    private static final String GEAR = "sliders", PADICON = "gamepad", VIEW = "hud_eye";
    private static final int ROLE_GEAR = -8;
    private static final int ROLE_STICK = -2, ROLE_LOOK = -3, ROLE_TOGGLE = -4, ROLE_EDIT = -5, ROLE_HOTBAR = -6,
        ROLE_NONE = -7;
    // Pushing the stick this far past its ring sprints (holds L3): no second thumb needed to run.
    private static final float SPRINT_AT = 1.25f, SPRINT_UNTIL = 1.10f;
    // The padlock: drag the stick straight ahead this far (in stick radii) and let go there, and the
    // character keeps sprinting forward on its own, so the thumb is free for the camera. A new touch on
    // the stick side, the padlock or the sprint button gives control back.
    private static final float LOCK_AT = 1.9f, LOCK_CONE = 0.5f;
    // Touch to act (GameSettings.touchAim), as in Minecraft Bedrock with a crosshair: the crosshair stays at
    // the centre of the screen and a finger on the view acts there, wherever it touches. A quick tap uses
    // or places (left trigger); resting for AIM_HOLD_MS breaks or attacks (right trigger) while the finger
    // stays; a finger that moves is just the camera.
    private static final int AIM_NONE = 0, AIM_PENDING = 1, AIM_HOLDING = 2;
    private static final long AIM_HOLD_MS = 220;
    private boolean touchAim;
    private int aimState;
    private float aimX, aimY;
    private boolean aimTriggerDown;
    private final Runnable aimHold = this::aimHoldStart;
    private Runnable onOpenSettings;
    private float controlScale = 1f;
    private boolean sprinting, runLocked, inLockZone;
    private float knobDX, knobDY;
    private static final String TAG = "HytalePad";
    private boolean debug;
    // Each finger's last position; a finger that starts on a button and drags also turns the camera.
    private final SparseArray<float[]> last = new SparseArray<>();
    private final java.util.HashSet<Integer> dragging = new java.util.HashSet<>();
    private int lookPointer = -1;
    // Layout editor: hold the eye button for half a second.
    private boolean editing;
    private Control selected;
    private float opacity = 1f;
    private final Runnable enterEditor = () -> setEditing(true);
    private final SparseArray<Integer> roles = new SparseArray<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final PorterDuffColorFilter glyphTint = new PorterDuffColorFilter(GLYPH, PorterDuff.Mode.SRC_IN);
    private final PorterDuffColorFilter accentTint = new PorterDuffColorFilter(ACCENT, PorterDuff.Mode.SRC_IN);
    private final RectF box = new RectF();
    private final Path shape = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private boolean enabled;
    // ready: SDL is up. physical: a real gamepad is connected. inGame: the game owns the pointer.
    private boolean ready, physical, inGame = true;
    private int menuVotes;
    private final InputManager.InputDeviceListener devices = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { refreshPhysical(); }
        @Override public void onInputDeviceRemoved(int id) { refreshPhysical(); }
        @Override public void onInputDeviceChanged(int id) { refreshPhysical(); }
    };
    // Reads the pointer state every 150 ms. Until the game has been seen owning the pointer once the
    // controls stay up, so a game that never does would not lose them; after that they follow it.
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (ready) {
                boolean captured = nativeCaptured();
                if (captured && !prefs.getBoolean("sawCapture", false))
                    prefs.edit().putBoolean("sawCapture", true).apply();
                boolean now = captured || !prefs.getBoolean("sawCapture", false);
                // Two readings in a row, so a flicker while a screen opens does not flash the controls.
                menuVotes = now == inGame ? 0 : menuVotes + 1;
                if (menuVotes >= 2) {
                    inGame = now;
                    menuVotes = 0;
                    if (debug)
                        Log.v(TAG, "pointer captured=" + captured + " -> controls " + (inGame ? "on" : "off"));
                    if (!inGame)
                        releaseAll();
                    invalidate();
                }
            }
            handler.postDelayed(this, 150);
        }
    };

    private float stickOriginX, stickOriginY, stickX, stickY;
    private boolean stickActive;
    private final Runnable lookStop = () -> { nativeAxis(RIGHT_X, 0); nativeAxis(RIGHT_Y, 0); };

    public GamepadOverlay(Context context) {
        super(context);
        prefs = context.getSharedPreferences("gamepad", Context.MODE_PRIVATE);
        controlScale = GameSettings.controlSize(context);
        touchAim = GameSettings.touchAim(context);
        loadLayout();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        text.setColor(LABEL);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        for (Control c : controls) {
            try (InputStream in = context.getAssets().open("icons/" + c.icon + ".png")) {
                c.bitmap = BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                c.bitmap = null; // the controller label is drawn instead
            }
        }
    }

    /** Restores the last state once the game has initialised SDL. */
    void restore() {
        ready = true;
        setGamepadEnabled(prefs.getBoolean("enabled", false));
    }

    private void setGamepadEnabled(boolean on) {
        if (!on)
            releaseAll();
        enabled = on;
        prefs.edit().putBoolean("enabled", on).apply();
        applyNative();
        invalidate();
    }

    // The virtual gamepad is attached only while the on-screen one is in use, never next to a real one.
    private void applyNative() {
        if (ready)
            nativeSetEnabled(enabled && !physical);
    }

    // The controls take part only during play, with the pad on and no real gamepad connected.
    private boolean live() {
        return enabled && !physical && (inGame || editing);
    }

    // The eye also stays while the pad is off, so it can be turned back on.
    private boolean visible(Control c) {
        // With touch aiming the attack/break button is replaced by holding a finger on the view; the
        // shield (defend, and place with a block) stays.
        if (touchAim && c.axis == RIGHT_TRIGGER)
            return false;
        return c.toggle || isAction(c) ? !physical && (!enabled || inGame || editing) : live();
    }

    private static boolean isGear(Control c) {
        return GEAR.equals(c.icon);
    }

    // Buttons that do something of their own instead of sending a gamepad button: the settings gear and
    // the camera view.
    private static boolean isAction(Control c) {
        return GEAR.equals(c.icon) || VIEW.equals(c.icon);
    }

    private Control actionControl;

    void setOnOpenSettings(Runnable open) {
        onOpenSettings = open;
    }

    /** The panel is open: let go of everything the fingers were holding. */
    void panelOpened() {
        releaseAll();
    }

    void panelClosed() {
        reloadSettings();
    }

    /** Picks up the control size set in the panel. */
    void reloadSettings() {
        controlScale = GameSettings.controlSize(getContext());
        touchAim = GameSettings.touchAim(getContext());
        invalidate();
    }

    /** Opens the layout editor (from the panel), turning the pad on first if it was off. */
    void startEditor() {
        if (!enabled)
            setGamepadEnabled(true);
        setEditing(true);
    }

    private void refreshPhysical() {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual())
                continue;
            int src = d.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                found = true;
                break;
            }
        }
        if (found == physical)
            return;
        physical = found;
        releaseAll();
        applyNative();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(devices, handler);
        refreshPhysical();
        debug = Log.isLoggable(TAG, Log.VERBOSE); // adb shell setprop log.tag.HytalePad VERBOSE
        handler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        im.unregisterInputDeviceListener(devices);
        handler.removeCallbacks(poll);
        releaseHotbarKey();
        super.onDetachedFromWindow();
    }

    // The nine hotbar slots sit in the bottom centre, one slot being 7.6% of the height wide.
    // The touch area takes the whole strip under the health bar, taller than the drawn slots.
    private int hotbarSlot(float x, float y) {
        float h = getHeight(), pitch = h * 0.0762f, left = getWidth() / 2f - 4.5f * pitch;
        if (y < h * 0.885f || x < left || x >= left + 9 * pitch)
            return -1;
        return (int) ((x - left) / pitch);
    }

    private float cx(Control c) {
        float x = c.fx * getHeight();
        return c.edge == EDGE_RIGHT ? getWidth() - x : c.edge == EDGE_CENTER ? getWidth() / 2f + x : x;
    }

    private float cy(Control c) { return c.fy * getHeight(); }
    private float half(Control c) {
        return c.side * getHeight() * (c.shape == TILE_SHAPE ? 1f : controlScale) / 2;
    }

    // Dragging a control in the editor stores its new centre in the same edge-relative units.
    private void moveTo(Control c, float x, float y) {
        c.fx = (c.edge == EDGE_RIGHT ? getWidth() - x : c.edge == EDGE_CENTER ? x - getWidth() / 2f : x) / getHeight();
        c.fy = y / getHeight();
    }

    // The touch area reaches 10% past the drawn shape.
    private Control hit(float x, float y) {
        for (Control c : controls) {
            if (!visible(c))
                continue;
            float r = half(c) * 1.1f;
            if (Math.abs(x - cx(c)) <= r && Math.abs(y - cy(c)) <= r)
                return c;
        }
        return null;
    }

    private boolean onToggle(float x, float y) {
        Control c = hit(x, y);
        return c != null && c.toggle;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        int id = e.getPointerId(index);
        float x = e.getX(index), y = e.getY(index);
        if (editing)
            return editTouch(action, id, x, y, e);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            Control top = hit(x, y);
            if (top != null && top.toggle) {
                roles.put(id, ROLE_TOGGLE);
                if (live())
                    handler.postDelayed(enterEditor, 600);
                return true;
            }
            if (top != null && isAction(top)) {
                roles.put(id, ROLE_GEAR);
                actionControl = top;
                top.down = true;
                invalidate();
                return true;
            }
            if (!live())
                return false; // menus, modals, pad off or a real gamepad: the game takes it as a mouse
            last.put(id, new float[] { x, y });
            Control c = hit(x, y);
            if (runLocked && (nearPadlock(x, y) || (c != null && c.button == LEFT_STICK && !c.chord))) {
                unlockRun(); // tapping the padlock or the sprint button stops the run
                roles.put(id, ROLE_NONE);
            } else if (c != null) {
                roles.put(id, indexOf(c));
                press(c, true);
            } else if (hotbarSlot(x, y) >= 0) {
                roles.put(id, ROLE_HOTBAR);
                tapHotbar(hotbarSlot(x, y));
            } else if (inStickZone(x, y) && !stickActive) {
                if (runLocked)
                    unlockRun(); // a thumb back on the stick takes over
                roles.put(id, ROLE_STICK);
                stickActive = true;
                stickOriginX = stickX = x;
                stickOriginY = stickY = y;
                knobDX = knobDY = 0;
            } else {
                roles.put(id, ROLE_LOOK);
                // The first finger on the camera keeps it; another one landing there does not take it over.
                if (lookPointer == -1 || roles.get(lookPointer) == null)
                    lookPointer = id;
                if (touchAim && lookPointer == id)
                    aimBegin(x, y);
            }
            if (debug)
                Log.v(TAG, "down id=" + id + " role=" + roles.get(id) + " x=" + (int) x + " y=" + (int) y
                    + " fingers=" + roles.size() + " locked=" + runLocked);
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            float slop = getHeight() * 0.03f;
            for (int i = 0; i < e.getPointerCount(); i++) {
                int pid = e.getPointerId(i);
                Integer role = roles.get(pid);
                float[] prev = last.get(pid);
                if (role == null || prev == null)
                    continue;
                float px = e.getX(i), py = e.getY(i);
                if (role == ROLE_STICK) {
                    moveStick(px, py);
                } else if (role == ROLE_LOOK || role >= 0) {
                    // A button finger turns the camera too once it has really moved (not on a tap).
                    if (role >= 0 && !dragging.contains(pid)) {
                        Control c = controls[role];
                        if (Math.abs(px - cx(c)) < half(c) + slop && Math.abs(py - cy(c)) < half(c) + slop)
                            continue;
                        dragging.add(pid);
                        prev[0] = px;
                        prev[1] = py;
                        continue;
                    }
                    if (lookPointer == -1 || lookPointer == pid || roles.get(lookPointer) == null) {
                        lookPointer = pid;
                        if (touchAim && pid == lookPointer && aimState == AIM_PENDING) {
                            // Still a tap or a hold until the finger really moves; then it is the camera.
                            if (Math.hypot(px - aimX, py - aimY) <= slop)
                                continue;
                            aimCancelPending();
                            prev[0] = px; // the camera starts from here, with no jump
                            prev[1] = py;
                        }
                        look(prev, px, py);
                    }
                }
                prev[0] = px;
                prev[1] = py;
            }
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            Integer role = roles.get(id);
            roles.remove(id);
            last.remove(id);
            dragging.remove(id);
            if (role != null && role == ROLE_TOGGLE)
                handler.removeCallbacks(enterEditor);
            if (role != null && !editing)
                release(role, action == MotionEvent.ACTION_CANCEL ? false : onToggle(x, y),
                    action != MotionEvent.ACTION_CANCEL && overAction(x, y));
            if (lookPointer == id) {
                lookPointer = -1;
                if (touchAim)
                    aimEnd(action != MotionEvent.ACTION_CANCEL);
                handler.removeCallbacks(lookStop);
                lookStop.run();
            }
            if (action == MotionEvent.ACTION_CANCEL)
                releaseAll();
            invalidate();
            return role != null;
        }
        return live();
    }

    // One hotbar key at a time. It is let go by a timer, and also whenever the controls are released
    // (a menu opens, the pad is turned off, the window goes away), so a key never stays pressed and
    // never leaves the stick, the camera or the buttons in a state of their own.
    private int hotbarKey = -1;
    private final Runnable hotbarUp = this::releaseHotbarKey;

    private void tapHotbar(int slot) {
        tapKey(android.view.KeyEvent.KEYCODE_1 + slot);
    }

    // A short key press through SDL's own Android key path (see the note above).
    private void tapKey(int keycode) {
        releaseHotbarKey();
        hotbarKey = keycode;
        org.libsdl.app.SDLActivity.onNativeKeyDown(hotbarKey);
        handler.postDelayed(hotbarUp, 60);
        if (debug)
            Log.v(TAG, "key " + keycode);
    }

    private void releaseHotbarKey() {
        handler.removeCallbacks(hotbarUp);
        if (hotbarKey >= 0) {
            org.libsdl.app.SDLActivity.onNativeKeyUp(hotbarKey);
            hotbarKey = -1;
        }
    }

    private int indexOf(Control c) {
        for (int i = 0; i < controls.length; i++)
            if (controls[i] == c)
                return i;
        return -1;
    }

    private void press(Control c, boolean down) {
        c.down = down;
        if (isAction(c))
            return; // no gamepad button behind it
        if (c.axis >= 0) {
            nativeAxis(c.axis, down ? AXIS_MAX : AXIS_MIN);
        } else if (c.chord) {
            // Hold the modifier first and let the game see it for a couple of frames.
            if (down) {
                nativeButton(RIGHT_SHOULDER, true);
                handler.postDelayed(() -> { if (c.down) nativeButton(c.button, true); }, 40);
            } else {
                nativeButton(c.button, false);
                handler.postDelayed(() -> nativeButton(RIGHT_SHOULDER, false), 40);
            }
        } else {
            nativeButton(c.button, down);
        }
    }

    private boolean overAction(float x, float y) {
        Control c = hit(x, y);
        return c != null && c == actionControl;
    }

    private void release(int role, boolean onToggle, boolean onAction) {
        if (role == ROLE_TOGGLE) {
            if (onToggle)
                setGamepadEnabled(!enabled);
        } else if (role == ROLE_GEAR) {
            Control a = actionControl;
            actionControl = null;
            if (a != null)
                a.down = false;
            if (onAction && a != null) {
                if (isGear(a)) {
                    if (onOpenSettings != null)
                        onOpenSettings.run();
                } else {
                    tapKey(android.view.KeyEvent.KEYCODE_V); // switch camera view
                }
            }
        } else if (role == ROLE_STICK) {
            stickActive = false;
            if (inLockZone) {
                // Let go on the padlock: keep running straight ahead.
                runLocked = true;
                inLockZone = false;
                nativeAxis(LEFT_X, 0);
                nativeAxis(LEFT_Y, AXIS_MIN);
                setSprint(true);
                performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                if (debug)
                    Log.v(TAG, "run locked");
            } else {
                setSprint(false);
                nativeAxis(LEFT_X, 0);
                nativeAxis(LEFT_Y, 0);
            }
            knobDX = knobDY = 0;
        } else if (role == ROLE_LOOK) {
            handler.removeCallbacks(lookStop);
            lookStop.run();
        } else if (role >= 0) {
            press(controls[role], false);
        }
    }

    private void releaseAll() {
        releaseHotbarKey();
        for (Control c : controls)
            if (c.down)
                press(c, false);
        stickActive = false;
        runLocked = false;
        inLockZone = false;
        knobDX = knobDY = 0;
        setSprint(false);
        nativeAxis(LEFT_X, 0);
        nativeAxis(LEFT_Y, 0);
        lookStop.run();
        aimReset();
        roles.clear();
        last.clear();
        dragging.clear();
        lookPointer = -1;
    }

    // The movement stick starts in the lower-left corner when touch aiming is on (the rest of the screen
    // is for aiming); otherwise anywhere in the left 45%.
    private boolean inStickZone(float x, float y) {
        float h = getHeight();
        if (!touchAim)
            return x < getWidth() * 0.45f;
        return x < padX() + 0.36f * h && y > h * 0.36f;
    }

    private void aimBegin(float x, float y) {
        aimState = AIM_PENDING;
        aimX = x;
        aimY = y;
        handler.postDelayed(aimHold, AIM_HOLD_MS);
        invalidate();
    }

    // The finger rested for AIM_HOLD_MS: hold the attack/mine trigger until it lifts.
    private void aimHoldStart() {
        if (aimState != AIM_PENDING)
            return;
        aimState = AIM_HOLDING;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        aimTriggerDown = true;
        nativeAxis(RIGHT_TRIGGER, AXIS_MAX);
        invalidate();
    }

    private void aimCancelPending() {
        if (aimState == AIM_PENDING) {
            handler.removeCallbacks(aimHold);
            aimState = AIM_NONE;
            invalidate();
        }
    }

    // The finger left: a tap (it never held or moved) uses or places; a hold ends.
    private void aimEnd(boolean released) {
        if (aimState == AIM_PENDING) {
            handler.removeCallbacks(aimHold);
            aimState = AIM_NONE;
            if (released) {
                nativeAxis(LEFT_TRIGGER, AXIS_MAX);
                handler.postDelayed(() -> nativeAxis(LEFT_TRIGGER, AXIS_MIN), 90);
            }
        } else if (aimState == AIM_HOLDING) {
            aimState = AIM_NONE;
            if (aimTriggerDown) {
                aimTriggerDown = false;
                nativeAxis(RIGHT_TRIGGER, AXIS_MIN);
            }
        }
        invalidate();
    }

    private void aimReset() {
        handler.removeCallbacks(aimHold);
        aimState = AIM_NONE;
        if (aimTriggerDown) {
            aimTriggerDown = false;
            nativeAxis(RIGHT_TRIGGER, AXIS_MIN);
        }
    }

    private void moveStick(float x, float y) {
        float max = getHeight() * 0.14f;
        float dx = x - stickOriginX, dy = y - stickOriginY;
        float len = (float) Math.hypot(dx, dy);
        if (!sprinting && len > max * SPRINT_AT)
            setSprint(true);
        else if (sprinting && len < max * SPRINT_UNTIL)
            setSprint(false);
        boolean zone = -dy > max * LOCK_AT && Math.abs(dx) < -dy * LOCK_CONE;
        if (zone != inLockZone) {
            inLockZone = zone;
            if (zone)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        // The knob follows the finger up to the padlock; the axes stop at the stick's radius.
        float show = Math.min(len, max * LOCK_AT);
        knobDX = len > 0 ? dx / len * show : 0;
        knobDY = len > 0 ? dy / len * show : 0;
        if (len > max) {
            dx = dx / len * max;
            dy = dy / len * max;
        }
        stickX = stickOriginX + dx;
        stickY = stickOriginY + dy;
        nativeAxis(LEFT_X, clamp(dx / max));
        nativeAxis(LEFT_Y, clamp(dy / max));
        if (debug)
            Log.v(TAG, "stick dx=" + (int) dx + " dy=" + (int) dy + " sprint=" + sprinting + " zone=" + zone);
    }

    // A swipe turns the camera: the finger speed becomes a right stick deflection
    // that falls back to zero shortly after the finger stops.
    private void look(float[] prev, float x, float y) {
        float scale = getHeight() * 0.02f;
        nativeAxis(RIGHT_X, clamp((x - prev[0]) / scale));
        nativeAxis(RIGHT_Y, clamp((y - prev[1]) / scale));
        handler.removeCallbacks(lookStop);
        handler.postDelayed(lookStop, 50);
    }

    private static int clamp(float v) {
        if (v > 1) v = 1;
        if (v < -1) v = -1;
        return (int) (v * AXIS_MAX);
    }

    private void unlockRun() {
        if (!runLocked)
            return;
        runLocked = false;
        setSprint(false);
        nativeAxis(LEFT_X, 0);
        nativeAxis(LEFT_Y, 0);
        if (debug)
            Log.v(TAG, "run unlocked");
        invalidate();
    }

    // The stick pad and the padlock above it (drawStick).
    private float padX() { return getHeight() * 0.2974f; }
    private float padY() { return getHeight() * 0.6868f; }
    private float padHalf() { return getHeight() * 0.177f; }
    private float lockX() { return padX(); }
    private float lockY() { return padY() - getHeight() * 0.30f; }
    private float lockR() { return getHeight() * 0.05f; }

    private boolean nearPadlock(float x, float y) {
        float r = lockR() * 1.6f;
        return Math.abs(x - lockX()) <= r && Math.abs(y - lockY()) <= r;
    }

    private void setSprint(boolean on) {
        if (sprinting == on)
            return;
        sprinting = on;
        Control l3 = controlFor(LEFT_STICK);
        if (l3 != null && l3.down != on && !isHeldByFinger(l3))
            press(l3, on);
    }

    private Control controlFor(int button) {
        for (Control c : controls)
            if (c.button == button && c.axis < 0 && !c.chord)
                return c;
        return null;
    }

    private boolean isHeldByFinger(Control c) {
        int i = indexOf(c);
        for (int k = 0; k < roles.size(); k++)
            if (roles.valueAt(k) == i)
                return true;
        return false;
    }

    // ---- layout editor --------------------------------------------------------------------------

    private static final String[] TOOLS = { "Size −", "Size +", "Opacity −", "Opacity +", "Reset", "Done" };

    private RectF toolRect(int i) {
        float h = getHeight(), w = getWidth();
        float bw = w * 0.13f, bh = h * 0.09f, gap = w * 0.008f;
        float total = TOOLS.length * bw + (TOOLS.length - 1) * gap;
        float left = (w - total) / 2 + i * (bw + gap), top = h * 0.45f;
        return new RectF(left, top, left + bw, top + bh);
    }

    private void setEditing(boolean on) {
        editing = on;
        selected = null;
        roles.clear();
        last.clear();
        dragging.clear();
        if (on) {
            releaseAll();
            setAlpha(1f);
        } else {
            saveLayout();
            setAlpha(opacity);
        }
        invalidate();
    }

    private boolean editTouch(int action, int id, float x, float y, MotionEvent e) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            for (int i = 0; i < TOOLS.length; i++)
                if (toolRect(i).contains(x, y)) {
                    tool(i);
                    return true;
                }
            Control c = hit(x, y);
            if (c != null) {
                selected = c;
                roles.put(id, ROLE_EDIT);
                last.put(id, new float[] { x - cx(c), y - cy(c) });
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && selected != null) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                float[] grab = last.get(e.getPointerId(i));
                if (grab == null)
                    continue;
                float r = half(selected);
                float nx = Math.max(r, Math.min(getWidth() - r, e.getX(i) - grab[0]));
                float ny = Math.max(r, Math.min(getHeight() - r, e.getY(i) - grab[1]));
                moveTo(selected, nx, ny);
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            roles.remove(id);
            last.remove(id);
        }
        return true;
    }

    private void tool(int i) {
        switch (i) {
            case 0: case 1:
                if (selected != null)
                    selected.side = Math.max(0.07f, Math.min(0.30f, selected.side * (i == 0 ? 0.9f : 1.1f)));
                break;
            case 2: case 3:
                opacity = Math.max(0.2f, Math.min(1f, opacity + (i == 2 ? -0.1f : 0.1f)));
                break;
            case 4:
                for (Control c : controls) {
                    c.fx = c.defaultFx;
                    c.fy = c.defaultFy;
                    c.side = c.defaultSide;
                }
                opacity = 1f;
                break;
            default:
                setEditing(false);
                return;
        }
        invalidate();
    }

    private void drawEditor(Canvas canvas) {
        float h = getHeight();
        stroke.setColor(0xC7ECF0F5);
        stroke.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(h * 0.032f);
        text.setColor(0xFFFFFFFF);
        canvas.drawText("Drag the buttons. Tap a button to change its size. Opacity: "
            + Math.round(opacity * 100) + "%", getWidth() / 2f, h * 0.40f, text);
        for (int i = 0; i < TOOLS.length; i++) {
            RectF r = toolRect(i);
            fill.setColor(i == TOOLS.length - 1 ? 0xE6F2B33D : 0xE6101418);
            canvas.drawRoundRect(r, h * 0.015f, h * 0.015f, fill);
            canvas.drawRoundRect(r, h * 0.015f, h * 0.015f, stroke);
            text.setColor(i == TOOLS.length - 1 ? 0xFF101418 : 0xFFF2F5F8);
            text.setTextSize(h * 0.028f);
            canvas.drawText(TOOLS[i], r.centerX(), r.centerY() + h * 0.01f, text);
        }
    }

    private void loadLayout() {
        opacity = prefs.getFloat("opacity", 1f);
        for (Control c : controls) {
            String v = prefs.getString("pos2." + c.icon, null);
            if (v == null)
                continue;
            String[] p = v.split(",");
            try {
                c.fx = Float.parseFloat(p[0]);
                c.fy = Float.parseFloat(p[1]);
                c.side = Float.parseFloat(p[2]);
            } catch (RuntimeException ignored) {
                // keep the default
            }
        }
        setAlpha(opacity);
    }

    private void saveLayout() {
        SharedPreferences.Editor edit = prefs.edit().putFloat("opacity", opacity);
        for (Control c : controls)
            edit.putString("pos2." + c.icon, c.fx + "," + c.fy + "," + c.side);
        edit.apply();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        fill.setStyle(Paint.Style.FILL);
        for (Control c : controls) {
            if (!visible(c))
                continue;
            drawControl(canvas, c);
            if (editing && c == selected) {
                float r = half(c) * 1.08f;
                box.set(cx(c) - r, cy(c) - r, cx(c) + r, cy(c) + r);
                stroke.setColor(ACCENT);
                stroke.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
                canvas.drawRoundRect(box, r * 0.32f, r * 0.32f, stroke);
            }
        }
        if (!live())
            return;
        if (editing) {
            drawEditor(canvas);
            return;
        }
        drawStick(canvas);
        if (aimState != AIM_NONE)
            drawAimRing(canvas);
    }

    // A ring on the touched point: thin while the finger decides between tap and hold, filled while it holds.
    private void drawAimRing(Canvas canvas) {
        float h = getHeight(), r = h * 0.05f;
        boolean holding = aimState == AIM_HOLDING;
        stroke.setColor(holding ? ACCENT : GLYPH);
        stroke.setStrokeWidth(Math.max(2f, h * 0.004f));
        stroke.setAlpha(holding ? 255 : 170);
        canvas.drawCircle(aimX, aimY, r, stroke);
        if (holding) {
            fill.setColor(ACCENT);
            fill.setAlpha(70);
            canvas.drawCircle(aimX, aimY, r, fill);
            fill.setAlpha(255);
        }
        stroke.setAlpha(255);
    }

    private void drawControl(Canvas canvas, Control c) {
        float x = cx(c), y = cy(c), r = half(c) * (c.down ? 0.94f : 1f);
        float line = Math.max(1.5f, r * (c.shape == TILE_SHAPE ? 0.09f : 0.028f));
        fill.setStyle(Paint.Style.FILL);
        stroke.setStrokeWidth(line);
        if (c.shape == TILE_SHAPE) {
            // Dark square with a lighter rim, corners about 14% of the side.
            box.set(x - r + line / 2, y - r + line / 2, x + r - line / 2, y + r - line / 2);
            fill.setColor(c.down ? TILE_FILL_DOWN : TILE_FILL);
            stroke.setColor(TILE_EDGE);
            canvas.drawRoundRect(box, r * 0.28f, r * 0.28f, fill);
            canvas.drawRoundRect(box, r * 0.28f, r * 0.28f, stroke);
        } else {
            // Square with its corners cut at 26% of the side.
            float cut = r * 2 * 0.26f;
            shape.reset();
            shape.moveTo(x - r + cut, y - r);
            shape.lineTo(x + r - cut, y - r);
            shape.lineTo(x + r, y - r + cut);
            shape.lineTo(x + r, y + r - cut);
            shape.lineTo(x + r - cut, y + r);
            shape.lineTo(x - r + cut, y + r);
            shape.lineTo(x - r, y + r - cut);
            shape.lineTo(x - r, y - r + cut);
            shape.close();
            fill.setColor(c.down ? OCT_FILL_DOWN : OCT_FILL);
            stroke.setColor(OCT_EDGE);
            canvas.drawPath(shape, fill);
            canvas.drawPath(shape, stroke);
        }

        if (c.bitmap != null) {
            // The glyph bitmaps carry a 4% margin on each side.
            float g = r * c.glyph / 0.92f;
            box.set(x - g, y - g, x + g, y + g);
            icon.setColorFilter(c.down ? accentTint : c.tint ? glyphTint : null);
            icon.setAlpha(c.toggle && !enabled ? 150 : 255);
            canvas.drawBitmap(c.bitmap, null, box, icon);
        } else if (isGear(c)) {
            drawSliders(canvas, x, y, r, c.down);
        } else if (PADICON.equals(c.icon)) {
            drawGamepadGlyph(canvas, x, y, r, c.down);
        } else if (!c.label.isEmpty()) {
            text.setColor(c.down ? ACCENT : LABEL);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(r * 0.8f);
            canvas.drawText(c.label, x, y + r * 0.28f, text);
        }
    }

    // A game controller: the glyph of the button that shows and hides the controls. It dims when they are hidden.
    private void drawGamepadGlyph(Canvas canvas, float x, float y, float r, boolean down) {
        int ink = down ? ACCENT : GLYPH;
        int alpha = enabled ? 255 : 150;
        stroke.setColor(ink);
        stroke.setAlpha(alpha);
        stroke.setStrokeWidth(Math.max(2f, r * 0.09f));
        fill.setColor(ink);
        fill.setAlpha(alpha);
        float w = r * 0.62f, h = r * 0.38f;
        box.set(x - w, y - h, x + w, y + h);
        canvas.drawRoundRect(box, h * 0.9f, h * 0.9f, stroke);
        // D-pad on the left.
        float arm = r * 0.17f, t = r * 0.055f;
        canvas.drawRect(x - w * 0.55f - arm, y - t, x - w * 0.55f + arm, y + t, fill);
        canvas.drawRect(x - w * 0.55f - t, y - arm, x - w * 0.55f + t, y + arm, fill);
        // Two buttons on the right.
        canvas.drawCircle(x + w * 0.38f, y - h * 0.2f, r * 0.065f, fill);
        canvas.drawCircle(x + w * 0.6f, y + h * 0.25f, r * 0.065f, fill);
        stroke.setAlpha(255);
        fill.setAlpha(255);
    }

    // Three sliders: the glyph of the settings button, which has no bitmap.
    private void drawSliders(Canvas canvas, float x, float y, float r, boolean down) {
        int ink = down ? ACCENT : GLYPH;
        stroke.setColor(ink);
        stroke.setStrokeWidth(Math.max(2f, r * 0.09f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(ink);
        float w = r * 0.58f;
        float[] ys = { -0.34f * r, 0f, 0.34f * r };
        float[] ks = { -0.30f, 0.34f, -0.04f };
        for (int i = 0; i < 3; i++) {
            canvas.drawLine(x - w, y + ys[i], x + w, y + ys[i], stroke);
            canvas.drawCircle(x + ks[i] * w * 1.6f, y + ys[i], r * 0.13f, fill);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    // A translucent square with a pixel-stepped diamond as the knob. The pad stays where it is; the
    // knob follows the thumb's offset from where it landed.
    private void drawStick(Canvas canvas) {
        float h = getHeight();
        float px = padX(), py = padY(), half = padHalf();
        fill.setColor(PAD_FILL);
        stroke.setColor(PAD_EDGE);
        stroke.setStrokeWidth(Math.max(1.5f, h * 0.002f));
        box.set(px - half, py - half, px + half, py + half);
        canvas.drawRoundRect(box, h * 0.008f, h * 0.008f, fill);
        canvas.drawRoundRect(box, h * 0.008f, h * 0.008f, stroke);

        float kx = px, ky = py, k = h * 0.0563f;
        if (stickActive) {
            // The knob follows the finger: inside the pad up to the stick's radius, then on to the padlock.
            kx += knobDX * 0.85f;
            ky += knobDY * 0.85f;
        } else if (runLocked) {
            ky -= h * 0.14f * LOCK_AT * 0.85f; // parked on the padlock
        }
        float reach = stickActive ? (float) Math.hypot(knobDX, knobDY) / (h * 0.14f) : 0;
        if (runLocked || reach > 0.6f)
            drawPadlock(canvas, runLocked || inLockZone, runLocked ? 1f : Math.min(1f, (reach - 0.6f) / 0.6f));
        stairDiamond(kx, ky, k, 9);
        fill.setColor(KNOB_RING);
        canvas.drawPath(shape, fill);
        stairDiamond(kx, ky, k * 0.74f, 7);
        fill.setColor(KNOB_FILL);
        canvas.drawPath(shape, fill);
    }

    // A padlock above the pad: it fades in as the stick is pushed, lights up in the lock zone and stays
    // lit while the run is locked.
    private void drawPadlock(Canvas canvas, boolean lit, float alpha) {
        float x = lockX(), y = lockY(), r = lockR();
        fill.setColor(lit ? 0xE6D69F41 : OCT_FILL);
        fill.setAlpha(Math.round(alpha * (lit ? 0xE6 : 0x87)));
        canvas.drawCircle(x, y, r * 1.45f, fill);
        stroke.setColor(lit ? 0xFFEDD2A2 : OCT_EDGE);
        stroke.setStrokeWidth(Math.max(1.5f, r * 0.12f));
        stroke.setAlpha(Math.round(alpha * 255));
        canvas.drawCircle(x, y, r * 1.45f, stroke);
        int ink = lit ? 0xFF141409 : GLYPH;
        // Shackle: an arch over the body.
        stroke.setColor(ink);
        stroke.setAlpha(Math.round(alpha * 255));
        stroke.setStrokeWidth(Math.max(2f, r * 0.2f));
        box.set(x - r * 0.45f, y - r * 0.95f, x + r * 0.45f, y - r * 0.05f);
        canvas.drawArc(box, 180, 180, false, stroke);
        // Body.
        fill.setColor(ink);
        fill.setAlpha(Math.round(alpha * 255));
        box.set(x - r * 0.7f, y - r * 0.25f, x + r * 0.7f, y + r * 0.75f);
        canvas.drawRoundRect(box, r * 0.18f, r * 0.18f, fill);
        fill.setColor(lit ? 0xFFD69F41 : 0xFF232320);
        fill.setAlpha(Math.round(alpha * 255));
        canvas.drawCircle(x, y + r * 0.22f, r * 0.12f, fill);
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    // A diamond whose edges climb in square steps, n steps per side.
    private void stairDiamond(float cx, float cy, float r, int n) {
        float s = r / n, x = cx, y = cy - r;
        shape.reset();
        shape.moveTo(x, y);
        for (int i = 0; i < n; i++) { x += s; shape.lineTo(x, y); y += s; shape.lineTo(x, y); }
        for (int i = 0; i < n; i++) { y += s; shape.lineTo(x, y); x -= s; shape.lineTo(x, y); }
        for (int i = 0; i < n; i++) { x -= s; shape.lineTo(x, y); y -= s; shape.lineTo(x, y); }
        for (int i = 0; i < n; i++) { y -= s; shape.lineTo(x, y); x += s; shape.lineTo(x, y); }
        shape.close();
    }
}
