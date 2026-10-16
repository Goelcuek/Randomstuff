################################################################################
# HERMLE_MT - Lock Axis computed in the post  (v10)
#
# v20 - X/Y/Z are rounded in the hook and passed to the template already
#       rounded, so the printed digits always match the feed decision
#       (C-only vs. Y moving). Fixes blocks that ran far too slow / too fast
#       when the hook's rounding and NX's formatting disagreed at the last
#       digit.
#
# v19 - Post feed calculation back ON (CUS_lock_feed 1). Machine test with
#       v18 confirmed standard 840D behavior: C-only (ACP) blocks run in
#       deg/min, and in mixed blocks F applies to X/Y/Z only - so a steady
#       F gives an uneven cutting speed. That is exactly what v17 corrects.
#
# v18 - Post feed calculation switched OFF by default (CUS_lock_feed 0):
#       on this machine the control already includes C in the feed
#       calculation, so the post's deg/min values made C-only moves run far
#       too fast. Lock Axis blocks (incl. C-only arcs) keep the library's F.
#
# v17 - Lock Axis feed calculated in the post. With G94 the control applies
#       F only to X/Y/Z; a block with a tiny Y move and a big C move would
#       run C at its maximum speed. Now every Lock Axis block gets its own F:
#         time  = true tool path on the part (MCS) / programmed feed
#         F     = output X/Y/Z distance / time          (in/min)
#                 or |C change| / time  if only C moves  (deg/min)
#       F is written at the end of the C word (C=DC(...) F...), so blocks
#       without a change are still suppressed. No FGROUP needed.
#       The separate F lines for C-only arcs are gone.
#
# v16 - Lock Axis arcs are no longer linearized by NX. Arcs come through
#       circular_move and are handled here:
#         - arc centered on the C axis (center X0 Y0, XY plane): the locked
#           X/Y never change, so the arc is written as pure C rotation
#           (+ Z for a helix) in a few blocks of max CUS_arc_max_c degrees,
#           C=ACP()/ACN() for the correct direction. Feed is converted:
#           pure C -> deg/min, with Z -> Z feed (same time as the arc).
#         - any other arc: linearized in Tcl to the linearization tolerance
#           and converted like linear moves (same result as before).
#
# v15 - akilli under TRAORI: C+R20 alone only changes the tool direction
#       (TRAORI keeps the tool on the same spot of the part). To copy the
#       feature around the C axis the positions are rotated too:
#         after TRAORI          ->  AROT Z=R20
#         before TRAFOOF / CYCLE800 / end of path  ->  ROT
#       Detected in literals and in template output; never stacked twice.
#       The copies rotate around the active work offset origin (G54 X0 Y0),
#       which therefore has to be the pattern center of the part. The C axis
#       position itself does not matter here (TRAORI/CYCLE800 use the
#       kinematics); only Lock Axis needs G54 on the C centerline.
#
# v14 - the library re-enables fifth_axis on its own (rotary handling), which
#       brought the plain C back next to C=DC(). MOM_enable_address /
#       MOM_disable_address / MOM_force are now wrapped: while akilli is on,
#       every library request for fifth_axis is redirected to
#       fifth_axis_akilli, so only one C word can ever be active.
#
# v13 - akilli detection no longer depends on the UDE event name: a Tcl
#       variable trace reacts the moment NX sets mom_akilli (any UDE, any
#       event). Value 1 / 1.0 / ON / YES / TRUE all count as on.
#       At end of path mom_akilli is set to 0 (not unset - unset would drop
#       the trace).
#
# v12 - "akilli" mode, turbo-compatible: when mom_akilli = 1 in an
#       operation, C is written as C=DC(<value>+R20).
#         - Lock Axis operations: via the existing C=DC() string word
#         - all other operations: the C word is switched to a second address
#           (fifth_axis_akilli, leader "C=DC(", trailer "+R20)") with
#           MOM_enable/disable_address. Formatting stays in NX, so turbo
#           output keeps working at full speed.
#       mom_akilli is cleared at end of path so it never carries over.
#
#   Definition File (one time, in every template that has the fifth_axis word:
#   linear_move, rapid moves, cycle_move, ...):
#       new address  fifth_axis_akilli : same FORMAT as fifth_axis,
#                    LEADER "C=DC("  TRAILER "+R20)"
#       add a word on it next to C with the same expression as fifth_axis
#
# v9 - one change against v8: calls made during the library's pretreatment
#      pass (caller = the library's own MOM_do_template wrapper) are passed
#      through untouched. The pretreatment runs on its own block set, where the
#      *_lock templates do not exist (cus_diag: invalid block template name).
#
# The hook owns the Lock Axis UDE; the library's polar mode is NOT used.
# For every motion in a Lock Axis operation the hook computes, from the MCS
# point (mom_mcs_goto), the machine X/Y and the C angle that put that point on
# the lock line, and prints them through the *_lock templates.
#
#   X locked at v :  X = v,  Y = side * sqrt(r^2 - v^2)
#   Y locked at v :  Y = v,  X = side * sqrt(r^2 - v^2)
#   C+ = table clockwise:  C = theta - phi
#       theta = angle of the point in MCS, phi = angle of the tool spot
#   C printed as C=DC(...) (modulo axis, shortest path), 0 <= C < 360
#   Cutting moves are subdivided to stay within the linearization tolerance;
#   arcs/helices are switched to linear output while the lock is on.
#   After end of path, C is turned back to the library's position.
#
# Scope: A = 0 (tool along Z), lock plane XY, lock axis XAXIS or YAXIS.
# Feed: left as the library outputs it (G94).
#
# Definition File:
#   linear_move_lock  (copy of linear_move)
#       X = $CUS_lock_x*$x_factor   Y = $CUS_lock_y   Z = $CUS_lock_z
#   cycle_move_lock   (copy of cycle_move)
#       X = $CUS_lock_x*$x_factor   Y = $CUS_lock_y   (Z unchanged)
#   in both: remove the fifth_axis word; add one word on a new String
#            address (no leader, modal), expression $CUS_lock_c_word
# Post Configurator: Polar Mode Status at Start of Program = Off
#
# Preconditions: G54 X/Y exactly on the C centerline, MCS aligned with the
# machine at C0. The offset check warns in the NC if that is not the case.
#
# CUS_debug 1 logs to %TEMP%\cus_diag.txt
#
# Replaces v1-v8 and the DIAG builds completely - do not mix.
################################################################################

set CUS_debug 1

# ------------------------------------------------------------- configuration
set CUS_lock_side    -1      ;# free-axis side: -1 = Y- with X locked (X- with Y locked)
set CUS_c_cw          1      ;# 1: C+ turns the table clockwise (C52); 0: counter-clockwise
set CUS_c_offset     ""      ;# added inside DC(), e.g. "+R20"; keep "" unless needed
set CUS_akilli_offset "+R20" ;# added inside DC() when mom_akilli = 1 (Lock Axis word;
                             ;#  the normal C word gets it from the address TRAILER)
set CUS_akilli_event  MOM_tei_akilli_prg ;# UDE that sets mom_akilli
set CUS_akilli_arot   "AROT Z=R20" ;# written after TRAORI in akilli operations
set CUS_akilli_per_op 1      ;# 1: akilli ends with each operation (reset at end of path)
                             ;# 0: akilli stays on until the UDE sets mom_akilli to 0
set CUS_decimals      3      ;# decimals for the C word (and the C rounding)
set CUS_max_step_deg  2.0    ;# max C change per subdivided block
set CUS_max_split     2000   ;# max blocks per CL move
set CUS_default_tol   0.0005 ;# used if mom_kin_linearization_tol is missing (part units)

array set CUS_lock_tmpl {linear_move linear_move_lock cycle_move cycle_move_lock circular_move linear_move_lock}
set CUS_arc_templates     {circular_move}
set CUS_lock_keep_arcs    1      ;# 1: arcs stay arcs (handled here), 0: NX linearizes (v15 behavior)
set CUS_arc_center_tol    0.001  ;# arc center this close to X0 Y0 -> pure C rotation
set CUS_arc_max_c         90.0   ;# max C degrees per block for centered arcs
set CUS_lock_feed         1      ;# 1: post calculates F per Lock Axis block (v17 logic)
                                 ;# 0: F as the library outputs it
set CUS_lin_decimals      4      ;# decimals of X/Y/Z in the output (must match the address format)
set CUS_out_prev          ""
set CUS_nosplit_templates {cycle_move}
set CUS_turbo_var mom_kin_is_turbo_output

# ---------------------------------------------------------------------- state
array set CUS_lock {active 0 axis "" value 0.0}
set CUS_prev ""
set CUS_last_c ""
set CUS_zoff 0.0
set CUS_checked_offset 0
array set CUS_saved {}
array set CUS_warned {}

# ------------------------------------------------------------------- logging
if {[info exists ::env(TEMP)]} {
    set CUS_diag_file [file join $::env(TEMP) cus_diag.txt]
} else {
    set CUS_diag_file [file join [pwd] cus_diag.txt]
}
if {$CUS_debug} { catch {close [open $CUS_diag_file w]} }

proc CUS_log {msg} {
    global CUS_debug CUS_diag_file
    if {!$CUS_debug} { return }
    catch {
        set f [open $CUS_diag_file a]
        puts $f $msg
        close $f
    }
}

proc CUS_warn {msg} {
    CUS_log "WARNING: $msg"
    catch {MOM_output_literal ";CUS WARNING: $msg"}
}

proc CUS_fail {msg} {
    CUS_log "ERROR: $msg"
    catch {MOM_output_literal ";CUS ERROR: $msg"}
    if {[llength [info commands MOM_abort]]} { MOM_abort "Lock Axis: $msg" }
    error "Lock Axis: $msg"
}

# ------------------------------------------------------------------- helpers
proc CUS_fmt {v} {
    set s [format "%.${::CUS_decimals}f" $v]
    if {[string first . $s] >= 0} {
        set s [string trimright $s 0]
        set s [string trimright $s .]
    }
    if {$s eq "-0" || $s eq "" || $s eq "-"} { set s 0 }
    return $s
}

proc CUS_norm360 {a} {
    set a [expr {fmod($a, 360.0)}]
    if {$a < 0.0} { set a [expr {$a + 360.0}] }
    set k [expr {pow(10.0, $::CUS_decimals)}]
    set a [expr {round($a * $k) / $k}]
    if {$a >= 360.0} { set a [expr {$a - 360.0}] }
    return $a
}

# ------------------------------------------------------------- akilli mode
proc CUS_akilli_on {} {
    global mom_akilli
    if {![info exists mom_akilli]} { return 0 }
    set v [string trim $mom_akilli]
    if {[string is double -strict $v]} { return [expr {$v == 1}] }
    return [string is true -strict $v]
}

# offset written inside DC(): akilli offset in akilli operations
proc CUS_c_off {} {
    global CUS_c_offset CUS_akilli_offset
    if {[CUS_akilli_on]} { return $CUS_akilli_offset }
    return $CUS_c_offset
}

# Swap the C word between the normal and the akilli address.
# Both words sit in the templates; only one address is enabled at a time.
# The library's own enable/disable/force calls on fifth_axis are redirected
# while akilli is on (wrappers below), and its wish is remembered so the
# normal state can be restored correctly.
set CUS_c_addr        fifth_axis
set CUS_c_addr_akilli fifth_axis_akilli
set CUS_akilli_state  ""
set CUS_c_lib_enabled 1        ;# what the library wants for fifth_axis

proc CUS_addr_raw {cmd addr} {
    # call the real MOM command, bypassing the wrappers
    if {[llength [info commands CUS_orig_$cmd]]} {
        return [catch {CUS_orig_$cmd $addr}]
    }
    return [catch {$cmd $addr}]
}

proc CUS_akilli_select {why} {
    global CUS_c_addr CUS_c_addr_akilli CUS_akilli_state CUS_c_lib_enabled
    set want [expr {[CUS_akilli_on] ? "akilli" : "normal"}]
    if {$want eq $CUS_akilli_state} { return }
    set err 0
    if {$want eq "akilli"} {
        incr err [CUS_addr_raw MOM_disable_address $CUS_c_addr]
        if {$CUS_c_lib_enabled} {
            incr err [CUS_addr_raw MOM_enable_address $CUS_c_addr_akilli]
        } else {
            incr err [CUS_addr_raw MOM_disable_address $CUS_c_addr_akilli]
        }
        set on $CUS_c_addr_akilli
    } else {
        incr err [CUS_addr_raw MOM_disable_address $CUS_c_addr_akilli]
        if {$CUS_c_lib_enabled} {
            incr err [CUS_addr_raw MOM_enable_address $CUS_c_addr]
        } else {
            incr err [CUS_addr_raw MOM_disable_address $CUS_c_addr]
        }
        set on $CUS_c_addr
    }
    if {$err} {
        CUS_warn "akilli: address $CUS_c_addr_akilli missing in the definition file"
        return
    }
    if {[llength [info commands CUS_orig_MOM_force]]} {
        catch {CUS_orig_MOM_force once $on}
    } else {
        catch {MOM_force once $on}
    }
    set CUS_akilli_state $want
    CUS_log "akilli ($why): C via $on (library wants fifth_axis enabled=$CUS_c_lib_enabled)"
}

# --- wrappers on the MOM address commands -----------------------------------
proc CUS_redirect_addrs {cmd addrs} {
    # returns the address list to really use for $cmd
    global CUS_c_addr CUS_c_addr_akilli CUS_akilli_state CUS_c_lib_enabled
    if {[lsearch -exact $addrs $CUS_c_addr] < 0} { return $addrs }
    if {$cmd eq "MOM_enable_address"}  { set CUS_c_lib_enabled 1 }
    if {$cmd eq "MOM_disable_address"} { set CUS_c_lib_enabled 0 }
    if {$CUS_akilli_state ne "akilli"} { return $addrs }
    set out {}
    foreach a $addrs {
        if {$a eq $CUS_c_addr} {
            if {$cmd eq "MOM_enable_address"} {
                lappend out $CUS_c_addr_akilli          ;# keep fifth_axis off
            } else {
                lappend out $CUS_c_addr $CUS_c_addr_akilli
            }
        } else {
            lappend out $a
        }
    }
    CUS_log "akilli: $cmd $addrs -> $out"
    return $out
}

foreach cmd {MOM_enable_address MOM_disable_address} {
    if {[llength [info commands $cmd]] && ![llength [info commands CUS_orig_$cmd]]} {
        rename $cmd CUS_orig_$cmd
        proc $cmd {args} [string map [list %CMD% $cmd] {
            set addrs [CUS_redirect_addrs %CMD% $args]
            if {![llength $addrs]} { return }
            return [CUS_orig_%CMD% {*}$addrs]
        }]
    }
}

# MOM_force <mode> addr ... : force the akilli word along with fifth_axis
if {[llength [info commands MOM_force]] && ![llength [info commands CUS_orig_MOM_force]]} {
    rename MOM_force CUS_orig_MOM_force
    proc MOM_force {mode args} {
        global CUS_c_addr CUS_c_addr_akilli CUS_akilli_state
        if {$CUS_akilli_state eq "akilli" && [lsearch -exact $args $CUS_c_addr] >= 0 \
                && [lsearch -exact $args $CUS_c_addr_akilli] < 0} {
            lappend args $CUS_c_addr_akilli
        }
        return [CUS_orig_MOM_force $mode {*}$args]
    }
}

# React whenever NX (or anyone) writes mom_akilli - independent of the UDE name
proc CUS_akilli_trace {args} {
    catch {CUS_log "akilli: mom_akilli = $::mom_akilli"}
    CUS_akilli_select "mom_akilli written"
}
trace add variable ::mom_akilli write CUS_akilli_trace

# --- akilli position rotation under TRAORI ---------------------------------
set CUS_arot_active 0

proc CUS_lit {txt} {
    # write a literal, bypassing the TRAORI/ROT detection wrapper
    if {[llength [info commands CUS_orig_MOM_output_literal]]} {
        CUS_orig_MOM_output_literal $txt
    } else {
        MOM_output_literal $txt
    }
}

proc CUS_rot_off {why} {
    global CUS_arot_active
    if {!$CUS_arot_active} { return }
    CUS_lit "ROT"
    set CUS_arot_active 0
    CUS_log "akilli: ROT ($why)"
}

# called BEFORE a block/literal is written
proc CUS_frame_before {txt} {
    if {[string index [string trimleft $txt] 0] eq ";"} { return }   ;# comment
    if {[regexp -nocase {(^|[\s;])(TRAFOOF|CYCLE800)} $txt]} {
        CUS_rot_off "before [string trim $txt]"
    }
}

# called AFTER a block/literal is written
proc CUS_frame_after {txt} {
    global CUS_arot_active CUS_akilli_state CUS_akilli_arot
    if {$CUS_akilli_state ne "akilli" || $CUS_arot_active} { return }
    if {[string index [string trimleft $txt] 0] eq ";"} { return }   ;# comment
    if {[regexp -nocase {(^|\s)TRAORI(\s|\(|$)} $txt]} {
        CUS_lit $CUS_akilli_arot
        set CUS_arot_active 1
        CUS_log "akilli: $CUS_akilli_arot after TRAORI"
    }
}

if {[llength [info commands MOM_output_literal]] && \
        ![llength [info commands CUS_orig_MOM_output_literal]]} {
    rename MOM_output_literal CUS_orig_MOM_output_literal
    proc MOM_output_literal {txt} {
        CUS_frame_before $txt
        set r [CUS_orig_MOM_output_literal $txt]
        CUS_frame_after $txt
        return $r
    }
}

# --------------------------------------------- kinematics save / set / restore
proc CUS_kin_set {name value} {
    global CUS_saved
    if {![info exists ::$name]} { CUS_log "kin: $name not present"; return 0 }
    if {![info exists CUS_saved($name)]} { set CUS_saved($name) [set ::$name] }
    set ::$name $value
    CUS_log "kin: $name $CUS_saved($name) -> $value"
    return 1
}

proc CUS_kin_restore {} {
    global CUS_saved
    set n 0
    foreach name [array names CUS_saved] {
        set ::$name $CUS_saved($name)
        incr n
    }
    array unset CUS_saved
    array set CUS_saved {}
    if {$n} {
        set rc [catch {MOM_reload_kinematics} err]
        CUS_log "kin restored ($n), reload rc=$rc $err"
    }
}

# ------------------------------------------------------------ lock on / off
proc CUS_lock_begin {axis value} {
    global CUS_lock CUS_prev CUS_last_c CUS_turbo_var CUS_checked_offset CUS_lock_side
    set CUS_lock(active) 1
    set CUS_lock(axis) $axis
    set CUS_lock(value) $value
    set CUS_prev ""
    set CUS_last_c ""
    set CUS_checked_offset 0
    set ::CUS_out_prev ""

    set changed 0
    if {[info exists ::$CUS_turbo_var]} {
        set tv [set ::$CUS_turbo_var]
        if {[string is integer -strict $tv]} { set off 0 } else { set off FALSE }
        if {$tv ne $off} { incr changed [CUS_kin_set $CUS_turbo_var $off] }
    } else {
        CUS_log "turbo: $CUS_turbo_var not present"
    }
    if {!$::CUS_lock_keep_arcs} {
        incr changed [CUS_kin_set mom_kin_arc_output_mode LINEAR]
        incr changed [CUS_kin_set mom_kin_helical_arc_output_mode LINEAR]
    }
    if {$changed} {
        set rc [catch {MOM_reload_kinematics} err]
        CUS_log "reload kinematics rc=$rc $err"
    }
    CUS_log "LOCK ON: axis=$axis value=$value side=$CUS_lock_side"
}

proc CUS_lock_end {why} {
    global CUS_lock CUS_prev
    if {!$CUS_lock(active)} { return }
    set CUS_lock(active) 0
    set CUS_prev ""
    set ::CUS_out_prev ""
    catch {MOM_force once F}      ;# next normal block writes its own feed again
    CUS_kin_restore
    CUS_log "LOCK OFF ($why)"
}

# Turn C back to where the library believes it is (after end of path)
proc CUS_restore_c {} {
    global CUS_last_c CUS_c_offset mom_out_angle_pos
    if {$CUS_last_c eq ""} { return }
    set c 0.0
    if {[info exists mom_out_angle_pos(1)]} { set c $mom_out_angle_pos(1) }
    set c [CUS_norm360 $c]
    if {abs($c - $CUS_last_c) < pow(10.0, -$::CUS_decimals)} { return }
    MOM_output_literal "G0 C=DC([CUS_fmt $c][CUS_c_off])"
    CUS_log "end of path: C back to [CUS_fmt $c] (library position)"
    set CUS_last_c $c
}

# --------------------------------------------------------- point conversion
proc CUS_convert {x y z} {
    global CUS_lock CUS_lock_side CUS_c_cw CUS_c_offset CUS_last_c CUS_zoff
    global CUS_lock_x CUS_lock_y CUS_lock_z CUS_lock_c CUS_lock_c_word
    set PI [expr {acos(-1.0)}]
    set v $CUS_lock(value)
    set r [expr {hypot($x, $y)}]
    if {$r < abs($v) - 1e-6} {
        CUS_fail "point X[CUS_fmt $x] Y[CUS_fmt $y] is closer to the C axis (R[CUS_fmt $r]) than the lock value [CUS_fmt $v]"
    }
    set f [expr {$r * $r - $v * $v}]
    if {$f < 0.0} { set f 0.0 }
    set free [expr {$CUS_lock_side < 0 ? -sqrt($f) : sqrt($f)}]
    if {$CUS_lock(axis) eq "XAXIS"} {
        set tx $v
        set ty $free
    } else {
        set tx $free
        set ty $v
    }
    if {$r < 1e-6} {
        if {$CUS_last_c ne ""} { set c $CUS_last_c } else { set c 0.0 }
    } else {
        set theta [expr {atan2($y, $x) * 180.0 / $PI}]
        set phi   [expr {atan2($ty, $tx) * 180.0 / $PI}]
        if {$CUS_c_cw} {
            set c [expr {$theta - $phi}]
        } else {
            set c [expr {$phi - $theta}]
        }
        set c [CUS_norm360 $c]
    }
    set CUS_lock_x $tx
    set CUS_lock_y $ty
    set CUS_lock_z [expr {$z + $CUS_zoff}]
    set CUS_lock_c $c
    set CUS_last_c $c
    set CUS_lock_c_word "C=DC([CUS_fmt $c][CUS_c_off])"
}

# ------------------------------------------------------------- Lock feed
proc CUS_ffmt {f} {
    if {$f < 0.00001} { set f 0.00001 }
    set s [format "%.5f" $f]
    set s [string trimright [string trimright $s 0] .]
    return $s
}

# Call right after CUS_convert, before the template.
#   p0/p1 : MCS start/end of this block (p0 "" = unknown)
#   dc    : C change of this block in degrees
#   lover : true path length if not the straight distance p0-p1 (arcs)
proc CUS_lock_feed_apply {p0 p1 dc {lover ""}} {
    global CUS_lock_feed CUS_out_prev CUS_lin_decimals x_factor
    global mom_feed_rate mom_feed_rate_output_mode
    set k [expr {pow(10.0, $CUS_lin_decimals)}]
    set xf 1.0
    if {[info exists x_factor] && [string is double -strict $x_factor]} { set xf $x_factor }
    # v20: round X/Y/Z HERE and hand exactly these values to the template.
    # NX then prints the same digits the feed decision is based on - no
    # rounding mismatch at the last digit (a mismatch made a "C only" block
    # carry a tiny Y move on the machine, or the other way round).
    # A tiny offset away from zero makes NX print the same digit whether its
    # format rounds or truncates.
    if {!$CUS_lock_feed} { return }
    set cur [list [expr {round($::CUS_lock_x * $xf * $k) / $k}] \
                  [expr {round($::CUS_lock_y * $k) / $k}] \
                  [expr {round($::CUS_lock_z * $k) / $k}]]
    set e [expr {0.01 / $k}]
    set ::CUS_lock_x [expr {([lindex $cur 0] + ([lindex $cur 0] < 0 ? -$e : $e)) / $xf}]
    set ::CUS_lock_y [expr { [lindex $cur 1] + ([lindex $cur 1] < 0 ? -$e : $e)}]
    set ::CUS_lock_z [expr { [lindex $cur 2] + ([lindex $cur 2] < 0 ? -$e : $e)}]
    set prev $CUS_out_prev
    set CUS_out_prev $cur
    if {$prev eq "" || $p0 eq ""} { return }
    if {![info exists mom_feed_rate] || ![string is double -strict $mom_feed_rate] \
            || $mom_feed_rate <= 0} { return }
    if {[info exists mom_feed_rate_output_mode] && \
            [lsearch -exact {IPM MMPM} [string toupper $mom_feed_rate_output_mode]] < 0} { return }

    if {$lover ne ""} { set L $lover } else { set L [CUS_vlen [CUS_vsub $p1 $p0]] }
    if {$L < 1e-9} { return }
    set t [expr {$L / $mom_feed_rate}]                 ;# minutes
    set dout [CUS_vlen [CUS_vsub $cur $prev]]
    if {$dout > 0.0} {
        set f [expr {$dout / $t}]                      ;# path axes move
    } elseif {abs($dc) > 1e-9} {
        set f [expr {abs($dc) / $t}]                   ;# only C: deg/min
    } else {
        return
    }
    append ::CUS_lock_c_word " F[CUS_ffmt $f]"
    catch {MOM_suppress once F}                        ;# library F off for this block
}

# shortest C difference in degrees
proc CUS_cdiff {c0 c1} {
    if {$c0 eq ""} { return 0.0 }
    set d [expr {fmod($c1 - $c0 + 540.0, 360.0) - 180.0}]
    return $d
}

# ------------------------------------------------------------------ arcs
proc CUS_vsub {a b} { return [list [expr {[lindex $a 0]-[lindex $b 0]}] [expr {[lindex $a 1]-[lindex $b 1]}] [expr {[lindex $a 2]-[lindex $b 2]}]] }
proc CUS_vadd {a b} { return [list [expr {[lindex $a 0]+[lindex $b 0]}] [expr {[lindex $a 1]+[lindex $b 1]}] [expr {[lindex $a 2]+[lindex $b 2]}]] }
proc CUS_vmul {a k} { return [list [expr {[lindex $a 0]*$k}] [expr {[lindex $a 1]*$k}] [expr {[lindex $a 2]*$k}]] }
proc CUS_vdot {a b} { return [expr {[lindex $a 0]*[lindex $b 0]+[lindex $a 1]*[lindex $b 1]+[lindex $a 2]*[lindex $b 2]}] }
proc CUS_vcross {a b} {
    foreach {ax ay az} $a break; foreach {bx by bz} $b break
    return [list [expr {$ay*$bz-$az*$by}] [expr {$az*$bx-$ax*$bz}] [expr {$ax*$by-$ay*$bx}]]
}
proc CUS_vlen {a} { return [expr {sqrt([CUS_vdot $a $a])}] }

# Arc from the NX variables, all in MCS. Returns a dict or "" if not usable.
#   keys: c (center) n (unit axis) vs (start radius vector in plane) r
#         s0 (start height along n) ang (signed sweep deg about n) h (helix rise)
proc CUS_arc_data {p0 p1} {
    global mom_pos_arc_center mom_pos_arc_axis mom_arc_direction mom_pos mom_mcs_goto
    foreach i {0 1 2} {
        if {![info exists mom_pos_arc_center($i)]} { return "" }
    }
    # output -> MCS offset (same as for the end point)
    set off {0 0 0}
    if {[info exists mom_pos(0)] && [info exists mom_mcs_goto(0)]} {
        set off [CUS_vsub [list $mom_pos(0) $mom_pos(1) $mom_pos(2)] \
                          [list $mom_mcs_goto(0) $mom_mcs_goto(1) $mom_mcs_goto(2)]]
    }
    set c [CUS_vsub [list $mom_pos_arc_center(0) $mom_pos_arc_center(1) $mom_pos_arc_center(2)] $off]
    set n {0 0 1}
    if {[info exists mom_pos_arc_axis(0)]} {
        set n [list $mom_pos_arc_axis(0) $mom_pos_arc_axis(1) $mom_pos_arc_axis(2)]
    }
    set nl [CUS_vlen $n]
    if {$nl < 1e-9} { return "" }
    set n [CUS_vmul $n [expr {1.0/$nl}]]

    set a [CUS_vsub $p0 $c]
    set b [CUS_vsub $p1 $c]
    set s0 [CUS_vdot $a $n]
    set s1 [CUS_vdot $b $n]
    set ap [CUS_vsub $a [CUS_vmul $n $s0]]
    set bp [CUS_vsub $b [CUS_vmul $n $s1]]
    set r  [CUS_vlen $ap]
    set r1 [CUS_vlen $bp]
    if {$r < 1e-6 || abs($r - $r1) > 0.001} {
        CUS_log "  arc: radius mismatch r0=[CUS_fmt $r] r1=[CUS_fmt $r1] - treated as line"
        return ""
    }
    set PI [expr {acos(-1.0)}]
    set ang [expr {atan2([CUS_vdot [CUS_vcross $ap $bp] $n], [CUS_vdot $ap $bp]) * 180.0 / $PI}]
    set dir "CCLW"
    if {[info exists mom_arc_direction]} { set dir [string toupper $mom_arc_direction] }
    if {$dir eq "CLW"} {
        if {$ang > -1e-6} { set ang [expr {$ang - 360.0}] }
    } else {
        if {$ang < 1e-6} { set ang [expr {$ang + 360.0}] }
    }
    # cross-check with NX's own sweep angle: if the other way round matches
    # better, the direction convention was the opposite one
    global mom_arc_angle
    if {[info exists mom_arc_angle] && [string is double -strict $mom_arc_angle]} {
        set want [expr {abs($mom_arc_angle)}]
        set alt  [expr {$ang > 0 ? $ang - 360.0 : $ang + 360.0}]
        if {abs(abs($alt) - $want) < abs(abs($ang) - $want)} {
            CUS_log "  arc: direction flipped to match mom_arc_angle=$mom_arc_angle"
            set ang $alt
        }
    }
    return [dict create c $c n $n vs $ap r $r s0 $s0 ang $ang h [expr {$s1 - $s0}]]
}

# Point on the arc at fraction u (Rodrigues rotation of vs about n)
proc CUS_arc_point {arc u} {
    set PI [expr {acos(-1.0)}]
    set t [expr {[dict get $arc ang] * $u * $PI / 180.0}]
    set n [dict get $arc n]
    set v [dict get $arc vs]
    set rv [CUS_vadd [CUS_vadd [CUS_vmul $v [expr {cos($t)}]] \
                               [CUS_vmul [CUS_vcross $n $v] [expr {sin($t)}]]] \
                     [CUS_vmul $n [expr {[CUS_vdot $n $v] * (1.0 - cos($t))}]]]
    set hgt [expr {[dict get $arc s0] + [dict get $arc h] * $u}]
    return [CUS_vadd [CUS_vadd [dict get $arc c] $rv] [CUS_vmul $n $hgt]]
}

# Points along a non-centered arc within the linearization tolerance
proc CUS_arc_points {arc} {
    global CUS_default_tol mom_kin_linearization_tol
    set tol $CUS_default_tol
    if {[info exists mom_kin_linearization_tol] && [string is double -strict $mom_kin_linearization_tol] \
            && $mom_kin_linearization_tol > 0} { set tol $mom_kin_linearization_tol }
    set r [dict get $arc r]
    set PI [expr {acos(-1.0)}]
    set x [expr {1.0 - $tol / $r}]
    if {$x < -1.0} { set x -1.0 }
    set step [expr {2.0 * acos($x) * 180.0 / $PI}]
    if {$step < 0.01} { set step 0.01 }
    set n [expr {int(ceil(abs([dict get $arc ang]) / $step))}]
    if {$n < 1} { set n 1 }
    set pts {}
    for {set k 1} {$k <= $n} {incr k} { lappend pts [CUS_arc_point $arc [expr {double($k)/$n}]] }
    return $pts
}

# Subdivide an MCS move so the Y/C interpolation stays within tolerance
proc CUS_split_points {p0 p1} {
    global CUS_max_step_deg CUS_max_split CUS_default_tol mom_kin_linearization_tol
    foreach {x0 y0 z0} $p0 break
    foreach {x1 y1 z1} $p1 break
    set PI [expr {acos(-1.0)}]
    set dx [expr {$x1 - $x0}]
    set dy [expr {$y1 - $y0}]
    set L [expr {hypot($dx, $dy)}]
    if {$L < 1e-9} { return [list $p1] }

    # closest XY distance of the move to the C axis
    set t [expr {-($x0 * $dx + $y0 * $dy) / ($L * $L)}]
    if {$t < 0.0} { set t 0.0 } elseif {$t > 1.0} { set t 1.0 }
    set rmin [expr {hypot($x0 + $t * $dx, $y0 + $t * $dy)}]
    set ra [expr {hypot($x0, $y0)}]
    set rb [expr {hypot($x1, $y1)}]
    set rmax [expr {$ra > $rb ? $ra : $rb}]

    set tol $CUS_default_tol
    if {[info exists mom_kin_linearization_tol] && [string is double -strict $mom_kin_linearization_tol] \
            && $mom_kin_linearization_tol > 0} {
        set tol $mom_kin_linearization_tol
    }
    set step [expr {$CUS_max_step_deg * $PI / 180.0}]
    if {$rmax > 1e-9} {
        set s [expr {sqrt(8.0 * $tol / $rmax)}]
        if {$s < $step} { set step $s }
    }
    if {$rmin < 1e-6} {
        CUS_warn "move passes through the C axis - lock output not possible there"
        set rmin 1e-6
    }
    set n [expr {int(ceil($L / ($rmin * $step)))}]
    if {$n < 1} { set n 1 }
    if {$n > $CUS_max_split} {
        CUS_warn "move needs $n blocks, limited to $CUS_max_split - check accuracy near the C axis"
        set n $CUS_max_split
    }
    set pts {}
    for {set k 1} {$k <= $n} {incr k} {
        set u [expr {double($k) / $n}]
        lappend pts [list [expr {$x0 + $u * $dx}] [expr {$y0 + $u * $dy}] [expr {$z0 + $u * ($z1 - $z0)}]]
    }
    return $pts
}

# ------------------------------------------------------------- event hooks
# Lock Axis UDE - owned by the hook; the library's handler is not called
if {[llength [info commands MOM_lock_axis]] && ![llength [info commands CUS_orig_MOM_lock_axis]]} {
    rename MOM_lock_axis CUS_orig_MOM_lock_axis
}
proc MOM_lock_axis {} {
    global mom_lock_axis mom_lock_axis_plane mom_lock_axis_value
    set vars {}
    foreach v [lsort [info globals mom_lock_axis*]] {
        if {[array exists ::$v]} { continue }
        lappend vars "$v=[set ::$v]"
    }
    CUS_log "lock_axis UDE: [join $vars {, }]"

    set ax ""
    if {[info exists mom_lock_axis]} { set ax [string toupper $mom_lock_axis] }
    if {$ax eq "" || $ax eq "OFF"} {
        CUS_lock_end "UDE off"
        return
    }
    if {$ax ne "XAXIS" && $ax ne "YAXIS"} {
        CUS_warn "Lock Axis $ax is not handled by the hook - passed to the library"
        if {[llength [info commands CUS_orig_MOM_lock_axis]]} { CUS_orig_MOM_lock_axis }
        return
    }
    if {[info exists mom_lock_axis_plane]} {
        set pl [string toupper $mom_lock_axis_plane]
        if {$pl ne "NONE" && ![string match *XY* $pl]} {
            CUS_warn "lock plane $mom_lock_axis_plane is not handled (XY only) - passed to the library"
            if {[llength [info commands CUS_orig_MOM_lock_axis]]} { CUS_orig_MOM_lock_axis }
            return
        }
    }
    set v 0.0
    if {[info exists mom_lock_axis_value] && [string is double -strict $mom_lock_axis_value]} {
        set v $mom_lock_axis_value
    }
    CUS_lock_begin $ax $v
}

# akilli UDE (if mom_akilli comes from a UDE): select the C address when it fires
if {[llength [info commands $CUS_akilli_event]]} {
    if {![llength [info commands CUS_orig_akilli_event]]} {
        rename $CUS_akilli_event CUS_orig_akilli_event
        proc $CUS_akilli_event {} {
            CUS_orig_akilli_event
            CUS_akilli_select "UDE"
        }
    }
} else {
    proc $CUS_akilli_event {} { CUS_akilli_select "UDE" }
}

# Start of path: clean state (start-event UDEs fire after this)
if {[llength [info commands MOM_start_of_path]] && ![llength [info commands CUS_orig_MOM_start_of_path]]} {
    rename MOM_start_of_path CUS_orig_MOM_start_of_path
    proc MOM_start_of_path {} {
        global CUS_lock CUS_last_c
        array unset ::CUS_warned
        array set ::CUS_warned {}
        if {$CUS_lock(active)} { CUS_lock_end "start of path" }
        set CUS_last_c ""
        CUS_rot_off "start of path"
        CUS_orig_MOM_start_of_path
        CUS_akilli_select "start of path"
    }
}

# End of path: library first, then C back and lock off
if {[llength [info commands MOM_end_of_path]] && ![llength [info commands CUS_orig_MOM_end_of_path]]} {
    rename MOM_end_of_path CUS_orig_MOM_end_of_path
    proc MOM_end_of_path {} {
        global CUS_last_c
        CUS_orig_MOM_end_of_path
        CUS_rot_off "end of path"
        CUS_restore_c
        CUS_lock_end "end of path"
        set CUS_last_c ""
        # akilli is per operation - never carry it into the next one
        if {$::CUS_akilli_per_op} {
            set ::mom_akilli 0            ;# trace switches back to the normal C address
            CUS_akilli_select "end of path"
        }
    }
}

# Block formatting: convert the lock-axis motion templates
if {![llength [info commands CUS_orig_MOM_do_template]]} {
    rename MOM_do_template CUS_orig_MOM_do_template
    proc MOM_do_template {args} {
        global CUS_lock CUS_lock_tmpl CUS_nosplit_templates CUS_prev CUS_zoff
        global CUS_checked_offset CUS_warned mom_mcs_goto mom_pos
        set tmpl [lindex $args 0]
        set call [linsert $args 0 CUS_orig_MOM_do_template]
        if {[catch {info level -1} caller]} { set caller "" }
        set cproc [lindex $caller 0]

        if {!$CUS_lock(active)} {
            # v15: TRAORI / TRAFOOF / CYCLE800 written through templates
            if {$cproc ne "MOM_do_template"} { CUS_frame_before $tmpl }
            set r [uplevel 1 $call]
            if {$cproc ne "MOM_do_template"} {
                CUS_frame_after $tmpl
                CUS_frame_after $r
            }
            return $r
        }

        # v9: pretreatment pass - the library formats through its own
        # MOM_do_template wrapper and its own block set; leave it untouched
        if {$cproc eq "MOM_do_template"} {
            CUS_log "  pretreatment pass-through: $tmpl"
            return [uplevel 1 $call]
        }

        if {![info exists CUS_lock_tmpl($tmpl)]} {
            if {[regexp -nocase {move|rapid|traverse|circ|helix|goto} $tmpl]} {
                if {![info exists CUS_warned($tmpl)]} {
                    set CUS_warned($tmpl) 1
                    CUS_warn "motion template '$tmpl' is not converted in a Lock Axis operation"
                }
            } else {
                CUS_log "  pass-through: $tmpl"
            }
            return [uplevel 1 $call]
        }

        if {![info exists mom_mcs_goto(0)] || ![info exists mom_mcs_goto(1)] \
                || ![info exists mom_mcs_goto(2)]} {
            CUS_fail "mom_mcs_goto missing for template '$tmpl'"
        }
        set x1 $mom_mcs_goto(0)
        set y1 $mom_mcs_goto(1)
        set z1 $mom_mcs_goto(2)
        set CUS_zoff 0.0
        if {[info exists mom_pos(2)]} { set CUS_zoff [expr {$mom_pos(2) - $z1}] }

        # once per operation: output frame must match the MCS in X/Y
        if {!$CUS_checked_offset && [info exists mom_pos(0)] && [info exists mom_pos(1)]} {
            set CUS_checked_offset 1
            set dx [expr {$mom_pos(0) - $x1}]
            set dy [expr {$mom_pos(1) - $y1}]
            CUS_log "  offset check: mom_pos - mcs = [CUS_fmt $dx] / [CUS_fmt $dy] / [CUS_fmt $CUS_zoff]"
            if {abs($dx) > 0.001 || abs($dy) > 0.001} {
                CUS_warn "output X/Y differ from MCS by [CUS_fmt $dx]/[CUS_fmt $dy] - G54/MCS not on the C centerline or a frame is active"
            }
        }

        catch {uplevel 1 {global CUS_lock_x CUS_lock_y CUS_lock_z CUS_lock_c_word}}
        set lcall [lreplace $call 1 1 $CUS_lock_tmpl($tmpl)]

        # v16: arcs
        global CUS_arc_templates CUS_arc_center_tol CUS_arc_max_c CUS_c_cw mom_feed_rate
        set arc ""
        if {[lsearch -exact $CUS_arc_templates $tmpl] >= 0 && $CUS_prev ne ""} {
            set arc [CUS_arc_data $CUS_prev [list $x1 $y1 $z1]]
        }
        if {$arc ne ""} {
            set cc [dict get $arc c]
            set nz [lindex [dict get $arc n] 2]
            if {abs($nz) > 0.9999 && hypot([lindex $cc 0], [lindex $cc 1]) < $CUS_arc_center_tol} {
                # --- centered arc: pure C rotation (+ Z), constant locked X/Y
                set ang [dict get $arc ang]
                set dth [expr {$ang * ($nz > 0 ? 1.0 : -1.0)}]      ;# about +Z
                set dc  [expr {$CUS_c_cw ? $dth : -$dth}]
                set acx [expr {$dc >= 0 ? "ACP" : "ACN"}]
                set nch [expr {int(ceil(abs($ang) / $CUS_arc_max_c))}]
                if {$nch < 1} { set nch 1 }
                set r [dict get $arc r]
                set PI [expr {acos(-1.0)}]
                set la  [expr {$r * abs($ang / $nch) * $PI / 180.0}]
                set len [expr {hypot($la, [dict get $arc h] / $nch)}]   ;# true path per block
                set result ""
                set pm $CUS_prev
                for {set k 1} {$k <= $nch} {incr k} {
                    set p [CUS_arc_point $arc [expr {double($k)/$nch}]]
                    foreach {px py pz} $p break
                    CUS_convert $px $py $pz
                    set ::CUS_lock_c_word "C=$acx\([CUS_fmt $::CUS_lock_c][CUS_c_off]\)"
                    CUS_lock_feed_apply $pm $p [expr {$dc / $nch}] $len
                    set pm $p
                    set code [catch {uplevel 1 $lcall} result]
                    if {$code} { CUS_fail "template $CUS_lock_tmpl($tmpl) failed: $result" }
                }
                set CUS_prev [list $x1 $y1 $z1]
                CUS_log [format "  arc centered on C: %s deg in %d block(s), r=%s, %s" \
                    [CUS_fmt $ang] $nch [CUS_fmt $r] $::CUS_lock_c_word]
                return $result
            }
        }

        set split [expr {$CUS_prev ne "" \
            && [lsearch -exact $CUS_nosplit_templates $tmpl] < 0 \
            && ![string match *decompose* $cproc]}]

        if {$arc ne ""} {
            # --- other arc: linearize here, then split each chord like a line
            set pts {}
            set prev $CUS_prev
            foreach q [CUS_arc_points $arc] {
                if {$split} {
                    set pts [concat $pts [CUS_split_points $prev $q]]
                } else {
                    lappend pts $q
                }
                set prev $q
            }
        } else {
            set pts [list [list $x1 $y1 $z1]]
            if {$split} { set pts [CUS_split_points $CUS_prev [list $x1 $y1 $z1]] }
        }
        set result ""
        set nofeed [expr {[lsearch -exact $CUS_nosplit_templates $tmpl] >= 0}]   ;# cycles keep their F
        set pm $CUS_prev
        foreach p $pts {
            foreach {px py pz} $p break
            set c0 $::CUS_last_c
            CUS_convert $px $py $pz
            if {!$nofeed} {
                CUS_lock_feed_apply $pm $p [CUS_cdiff $c0 $::CUS_lock_c]
            } else {
                set ::CUS_out_prev ""
            }
            set pm $p
            set code [catch {uplevel 1 $lcall} result]
            if {$code} { CUS_fail "template $CUS_lock_tmpl($tmpl) failed: $result" }
        }
        set CUS_prev [list $x1 $y1 $z1]
        CUS_log [format "  %s via %s: %d block(s), MCS X%s Y%s -> X%s Y%s %s  <%s>" \
            $tmpl $cproc [llength $pts] [CUS_fmt $x1] [CUS_fmt $y1] \
            [CUS_fmt $::CUS_lock_x] [CUS_fmt $::CUS_lock_y] $::CUS_lock_c_word $result]
        return $result
    }
}
