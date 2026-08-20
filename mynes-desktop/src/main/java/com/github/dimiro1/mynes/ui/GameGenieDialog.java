package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.cheat.InvalidGameGenieCodeException;
import net.miginfocom.swing.MigLayout;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.Font;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hacks &gt; Game Genie...: the codes in the cartridge slot, listed and editable.
 * <p>
 * There is no Save or Cancel, the same as the other two settings dialogs. Every change takes effect
 * the moment it is made -- the frame gets told through {@code onChange} and posts it onto the
 * emulation thread -- which is what makes trying a code out a matter of typing it rather than
 * closing a dialog first. Six letters or eight, and a code that is neither is refused by leaving it
 * in the box selected, which is the same answer the debugger's address field gives.
 * <p>
 * The list is this window's own copy rather than a view onto the device, for the reason the
 * debugger's points panel keeps one: the device is read by the emulation thread on every instruction
 * the processor fetches, and nothing on this thread may touch it. The whole list goes over on each
 * change and the frame replays it, so the rule about what two codes for one address mean is applied
 * in one place rather than agreed between two.
 * <p>
 * Codes are not remembered between sessions. One is written for a particular cartridge and means
 * something else entirely in the next one, so there is nowhere sensible to keep them but here.
 */
public class GameGenieDialog extends JDialog {
    private static final Font MONOSPACED = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    /**
     * What the line under the box says until it has something better to say. The alphabet is worth
     * printing because it is the surprising part: there is no B, C, D or R in a code, so half of what
     * a misread one turns into is not a code at all.
     */
    private static final String HINT =
            "Six letters or eight, from APZLGITYEOXUKSVN. Eight name a byte the cartridge has to"
                    + " answer with, which pins the code to one bank.";

    private final DefaultListModel<GameGenieCode> model = new DefaultListModel<>();
    private final JTextField entry = new JTextField(10);
    private final JLabel message = new JLabel();

    private final Consumer<List<GameGenieCode>> onChange;

    public GameGenieDialog(
            final Frame owner,
            final List<GameGenieCode> codes,
            final Consumer<List<GameGenieCode>> onChange) {
        super(owner, "Game Genie", true);

        this.onChange = onChange;

        codes.forEach(model::addElement);

        init();
    }

    private void init() {
        setResizable(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new MigLayout());

        var list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(8);
        list.setFont(MONOSPACED);

        entry.setFont(MONOSPACED);

        var add = new JButton("Add");
        var remove = new JButton("Remove");
        var removeAll = new JButton("Remove All");
        var close = new JButton("Close");

        // Typing a code and pressing Enter is the whole gesture, so the button is the alternative
        // rather than the way in.
        add.addActionListener(e -> addTyped());
        entry.addActionListener(e -> addTyped());

        remove.addActionListener(e -> {
            var selected = list.getSelectedValue();

            if (selected != null) {
                model.removeElement(selected);
                say(selected.text() + " taken out");
                changed();
            }
        });

        removeAll.addActionListener(e -> {
            model.clear();
            say("all of them taken out");
            changed();
        });

        close.addActionListener(e -> dispose());

        // What a code does, spelled out beside it, because six letters say nothing about themselves
        // and a mistyped one is a valid code for somewhere else entirely.
        add(new JLabel("Codes"), "span 3, wrap");
        add(new JScrollPane(list), "span 3, growx, wrap");
        add(entry, "growx");
        add(add);
        add(remove, "wrap");
        // Fixed width and three lines' worth of room reserved from the start. The width is what makes
        // the wrapping predictable, and the height is so that a refusal does not resize the window
        // out from under the pointer that is about to press Add again -- the longest thing this can
        // say is the list of the sixteen letters, which is three lines here.
        add(message, "span 3, wmin 340, wmax 340, hmin 48, top, wrap");
        add(removeAll, "span 3, split 2, growx");
        add(close, "growx");

        say(HINT);

        pack();
        setLocationRelativeTo(getOwner());
    }

    /**
     * Takes what is in the box, and does nothing at all with a word that is not a code -- beyond
     * saying why, since the reason a code was refused is not guessable from six letters.
     */
    private void addTyped() {
        var typed = entry.getText().trim();

        if (typed.isEmpty()) {
            return;
        }

        final GameGenieCode code;

        try {
            code = GameGenieCode.decode(typed);
        } catch (InvalidGameGenieCodeException e) {
            say(e.getMessage());
            entry.selectAll();

            return;
        }

        // One address holds one code, the way the cartridge port does. The device applies the same
        // rule when the frame replays this list; here it is only so that the list says the truth.
        var replaced = removeCodeAt(code.address());

        model.addElement(code);
        entry.setText("");

        say(replaced == null
                ? code.toString()
                : code + ", replacing " + replaced.text() + " at the same address");

        changed();
    }

    private GameGenieCode removeCodeAt(final int address) {
        for (var i = 0; i < model.size(); i++) {
            if (model.get(i).address() == address) {
                return model.remove(i);
            }
        }

        return null;
    }

    private void changed() {
        var codes = new ArrayList<GameGenieCode>(model.size());

        for (var i = 0; i < model.size(); i++) {
            codes.add(model.get(i));
        }

        onChange.accept(List.copyOf(codes));
    }

    /**
     * The line under the box, which is where a refusal goes rather than into a dialog of its own: a
     * mistyped code is an ordinary thing to do and being stopped by a modal for it would be tiresome.
     * HTML so that a long reason wraps instead of widening the window.
     */
    private void say(final String what) {
        message.setText("<html>" + what.replace("<", "&lt;") + "</html>");
    }
}
