################################################################################
# HERMLE_SIEMENS_TEI.tcl   -   v2  (rewrite, STARTING POINT)
# Hermle C52 UMT  -  SINUMERIK 840D sl  -  NX Post Configurator custom layer
#
# Replaces the old HERMLE_SIEMENS_TEI.tcl AND the pasted custom code
# (shimset_custom.tcl, hermle_lock_axis_v21.tcl). Load ONLY this file.
#
# Markers in this file:
#   [OK]      from the tested custom code, logic unchanged
#   [PHOTO]   rewritten from the photographed parts of the old file - compare
#   [VERIFY]  my assumption, that part of the old file was not visible
#   [TODO]    not seen yet - paste the old code or send a photo
#   TEI_todo  stub procs write ";!! TODO <name>" into the NC, so a test post
#             shows exactly where they are called
#
# Layout
#    1  Configuration
#    2  State (program / item)
#    3  Helpers
#    4  Operation flow - MOM_start_of_path / MOM_end_of_path (ONE table)
#    5  Program start: parameters, header (antet), tool list INI
#    6  Tool change + BLUM laser
#    7  Item start header (TP label, CYCLE832, CYCLE800, sub start)
#    8  CYCLE800
#    9  CYCLE832 state + tapping
#   10  Akilli: counter init (UDE) and loop end
#   11  End of item: M9 M5, messages, GAGECUT, akilli loop, Item_No
#   12  GRUP / GRUPSON
#   13  TURN: SHIMSET, first move F200, safety lines, spindle/coolant stop
#   14  GAGECUT wear offset check
#   15  Insert text -> 840D
#   16  Coolant / spindle direction reset
#   17  Probe: points, bore, PP7000 TEIFLAT, PP7300 TEICENTER, C0 TEICLOCK
#   18  Lock Axis + akilli C=DC(..+R20) engine (v21)
################################################################################


#==============================================================================
# 1  CONFIGURATION
#==============================================================================
# --- debug: 1 = an error in one of our procs does not stop the post; it is
#     reported (step name + Tcl error) in the NX Information window, in
#     %TEMP%\TEI_errors.txt and as ";!! ERROR" in the NC. Set 0 when stable.
set TEI_DEBUG           1

# --- program
set TEI_FIRST_ITEM_NO   1                       ;# [VERIFY] first TP label number
set TEI_HOME            "L_M54"                 ;# home / reset macro
set TEI_CYCLE832_ON     "CYCLE832(0.001,1,1)"   ;# select (milling items)
set TEI_CYCLE832_OFF    "CYCLE832(0,0,1)"       ;# deselect (before tapping)

# --- keys in the operation name / tool description
set TEI_KEY_GRUP        GRUP
set TEI_KEY_GRUPSON     GRUPSON
set TEI_KEY_GAGECUT     GAGECUT
set TEI_KEY_SHIMSET     SHIMSET
set TEI_KEY_PROBE       PROBE                   ;# in mom_tool_description

# --- TURN
set TURN_SAFETY_LINES   {"ROT" "G90 G94 G40 G700"}
set SHIMSET_BLOCK_ADDRS {M_coolant G_spin}      ;# words kept out of SHIMSET items
set TURN_FIRST_MOVE_FEED 200.0
set TURN_FEED_DEBUG     0
set SPINDLE_DEFAULT_DIR CLW

# --- GAGECUT wear check  ($TC_DPxx numbers)
set GAGE_WEAR_DP_X      12                      ;# TURN X wear
set GAGE_WEAR_DP_Z      13                      ;# TURN Z wear
set GAGE_MILL_DP_Z      12                      ;# MILL length 1 wear
set GAGE_MILL_DP_R      15                      ;# MILL radius wear
set GAGE_MILL_TOL_R     0.004
set GAGE_MILL_TOL_Z     0.004

# --- tapping events (NX cycle events that come right before CYCLE84)
set TAP_EVENTS          {MOM_tap MOM_tap_float MOM_tap_deep MOM_tap_break_chip}

# --- probe
set PROBE_CLOSE_OUTPUT  1   ;# [VERIFY] probe items: main file closed at start,
                            ;#          reopened at end (old end code reopened it)


#==============================================================================
# 2  STATE
#==============================================================================
# Program level - set once here, changed by the item logic
set Item_No             $TEI_FIRST_ITEM_NO
set Antet_Flag          0
set probe_flag          0        ;# PROBE tool is in the spindle
set prev_op_gage        0        ;# previous item was GAGECUT
set prev_op_shimset     0        ;# previous item was SHIMSET
set counter             1        ;# probe GETPOINT index          [VERIFY]
set sub_loc             ""
set probe_loc           ""
set probe_probing_routine ""
set TEI_c832            ""       ;# CYCLE832 state: "" unknown / on / off
set PROBE_file_closed   0        ;# main file closed during a probe item
set mom_akilli          0

# Item level - reset at every start of path
proc TEI_item_reset {} {
  set ::Baslik_basildi   0       ;# item header written
  set ::cycle800_flag    0       ;# CYCLE800 written
  set ::takim_degistimi  0       ;# tool change written in this item
  set ::ilk_hareket      0
  set ::tap_hsm_done     0       ;# CYCLE832 off written for the tap
  set ::tap_hsm_was_on   0
  set ::tap_hsm_restored 0
  catch {unset ::initial_move_points}
}


#==============================================================================
# 3  HELPERS
#==============================================================================
proc TEI_lit {args} { foreach l $args { MOM_output_literal $l } }

proc TEI_todo {name} { MOM_output_literal ";!! TODO $name" }

# Number for literals (was a helper in the old file)               [VERIFY]
#  fmt "I.D": D decimals. Trailing zeros removed, decimal point kept on
#  whole numbers, zero as 0:   18 -> 18.   0 -> 0   12.3450 -> 12.345
#  Rebuilt from the posted output "G0 A0 C=(18.+R20)".
if {![llength [info commands format_decimal_string]]} {
  proc format_decimal_string {v {fmt 4.4}} {
    set dec [lindex [split $fmt .] 1]
    if {![string is integer -strict $dec]} { set dec 4 }
    set s [format "%.${dec}f" $v]
    if {[string first . $s] >= 0} { set s [string trimright $s 0] }
    if {$s in {"0." "-0." "." "-."}} { return "0" }
    return $s
  }
}

# Error window in the old UYARI style, stops the post
proc TEI_abort {args} {
  set s "\n\n******************************     U   Y   A   R   I     ******************************\n\n"
  set m $s
  foreach l $args { append m "     $l \n" }
  append m $s
  set ::TEI_aborting 1              ;# the debug guard lets this one through
  MOM_abort $m
}

# Debug guard: report an error of one of our procs and carry on
set TEI_aborting 0
set TEI_err_file [file join [expr {[info exists ::env(TEMP)] ? $::env(TEMP) : "."}] TEI_errors.txt]
catch {close [open $TEI_err_file w]}

proc TEI_report {where err} {
  global TEI_err_file mom_operation_name
  set op [expr {[info exists mom_operation_name] ? $mom_operation_name : "?"}]
  set info [join [lrange [split $::errorInfo "\n"] 0 4] " | "]
  set line "TEI ERROR in $where (op $op): $err  ##  $info"
  catch {MOM_output_to_listing_device $line}
  catch {set f [open $TEI_err_file a] ; puts $f $line ; close $f}
  catch {MOM_output_literal ";!! ERROR $where: [string map {"\n" " "} $err]"}
}

proc TEI_call {p args} {
  if {!$::TEI_DEBUG} { return [uplevel 1 [list $p {*}$args]] }
  set code [catch {uplevel 1 [list $p {*}$args]} r]
  if {$code == 1} {
    if {$::TEI_aborting} { error $r $::errorInfo }
    TEI_report $p $r
    return ""
  }
  return $r
}

proc TEI_name_has {key} {
  global mom_operation_name
  if {![info exists mom_operation_name]} { return 0 }
  return [expr {[string first [string toupper $key] [string toupper $mom_operation_name]] >= 0}]
}

proc TEI_is_turn {} {
  global mom_machine_mode
  return [expr {[info exists mom_machine_mode] && [string equal -nocase $mom_machine_mode "TURN"]}]
}

proc TEI_is_probe {} {
  global mom_tool_description TEI_KEY_PROBE
  return [expr {[info exists mom_tool_description] && \
                [string first $TEI_KEY_PROBE $mom_tool_description] >= 0}]
}

proc TEI_is_gage {} {
  global TEI_KEY_GAGECUT TEI_KEY_SHIMSET
  return [expr {[TEI_name_has $TEI_KEY_GAGECUT] && ![TEI_name_has $TEI_KEY_SHIMSET]}]
}

# akilli on? (1 / 1.0 / TRUE / ON / YES) - same test as the Lock Axis engine
proc TEI_akilli_on {} { return [CUS_akilli_on] }

proc TEI_next_tool_change {} {
  global mom_next_oper_has_tool_change
  if {[info exists mom_next_oper_has_tool_change]} { return $mom_next_oper_has_tool_change }
  return "YES"
}

proc get_next_oper_name {} {
  global mom_operation_name mom_operation_name_list
  if {![info exists mom_operation_name_list]} { return "" }
  set ops [regexp -all -inline {\S+} $mom_operation_name_list]
  set i [lsearch -exact $ops $mom_operation_name]
  if {$i < 0 || $i + 1 >= [llength $ops]} { return "" }
  return [lindex $ops [expr {$i + 1}]]
}

# Extra message UDE: MSG for every message whose location matches   [PHOTO]
proc TEI_extra_msg {loc} {
  foreach k {1 2} {
    set mv ::mom_Extra_mesaj_$k
    set lv ::mom_Extra_mesaj_loc_$k
    if {[info exists $mv] && [info exists $lv] && [set $lv] eq $loc} {
      MOM_output_literal "MSG(\"[set $mv]\")"
    }
  }
}

# M9 M5 with the post's own codes   [PHOTO]
proc TEI_m9_m5 {} {
  global mom_sys_coolant_code mom_sys_spindle_direction_code
  MOM_output_literal "M$mom_sys_coolant_code(OFF) M$mom_sys_spindle_direction_code(OFF)"
}


#==============================================================================
# 4  OPERATION FLOW
#  The complete order of one operation. Change the order HERE only.
#  start_pre   before the library's start of path
#  start_post  after it  (start-event UDEs, tool change, moves follow later)
#  end_pre     before the library's end of path
#  end_post    after it
#==============================================================================
set TEI_FLOW(start_pre) {
  LOCK_start_pre          COOL_take_snapshot      TEI_item_reset
  SHIMSET_start           GRUP_start              PROBE_start
  SHIMSET_block_words
}
set TEI_FLOW(start_post) {
  LOCK_start_post         TURN_safety_lines       GRUP_start_post
}
set TEI_FLOW(end_pre) {
  GRUP_end_check          TAP_hsm_restore
}
set TEI_FLOW(end_post) {
  LOCK_frames_off         PROBE_end               TEI_end_of_item
  SHIMSET_unblock_words   TURN_stop_spindle       TEI_home
  GAGE_end                LOCK_end_post           TEI_item_done
  COOL_restore            SPINDLE_reset           GRUP_end_done
}

proc TEI_run {stage} { foreach p $::TEI_FLOW($stage) { TEI_call $p } }

if {![llength [info commands TEI_lib_start_of_path]]} {
  rename MOM_start_of_path TEI_lib_start_of_path
  proc MOM_start_of_path {} {
    TEI_run start_pre
    TEI_lib_start_of_path
    TEI_run start_post
  }
}
if {![llength [info commands TEI_lib_end_of_path]]} {
  rename MOM_end_of_path TEI_lib_end_of_path
  proc MOM_end_of_path {} {
    TEI_run end_pre
    TEI_lib_end_of_path
    TEI_run end_post
  }
}


#==============================================================================
# 5  PROGRAM START
#==============================================================================
# --- parameter definition / DEF lines                              [TODO]
# Old file: LIB_GE_command_buffer_edit_replace LIB_CTRL_parameter_definition
#           PARAMETER @NEWLINE1 TEI_Hermle_I... (name cut off in the photo)
# Paste TEI_Hermle_Init here and enable the buffer edit with the right name.
proc TEI_Hermle_Init {} { TEI_todo TEI_Hermle_Init }
#LIB_GE_command_buffer_edit_replace LIB_CTRL_parameter_definition PARAMETER @NEWLINE1 TEI_Hermle_Init TEI_Hermle_InitTAG

# --- program header (antet), once                                  [PHOTO]
proc TEI_antet_lines {} {
  global mom_Parca_No mom_Op_No mom_Rev_No mom_Rev_Tarihi
  TEI_lit \
    ";(****   $mom_Parca_No - OP$mom_Op_No   ****)" \
    ";(***  HERMLE 840D SL  ***)" \
    ";(***  REV$mom_Rev_No / $mom_Rev_Tarihi  ***)" \
    ";(*----------------------------------------------------*)" \
    ";(*** HIZMETE OZEL ***)" \
    ";(*----------------------------------------------------*)" \
    "M25 M27" \
    ";(*================================================*)"
}

proc TEI_Antet {} {
  global Antet_Flag PROBE_file_closed
  if {$Antet_Flag} { return }
  if {$PROBE_file_closed} { return }   ;# probe item: PROBE_end writes it
  TEI_antet_lines
  set Antet_Flag 1
}
LIB_GE_command_buffer_edit_prepend LIB_SPF_operation_header_comment HEADER_COMMENT TEI_Antet TEI_AntetTAG

# --- item description                                              [TODO]
proc TEI_Item_Aciklama {} { TEI_todo TEI_Item_Aciklama }

# --- tool list for the magazine (TAKIM_BILGILERI_OP<n>.INI)        [PHOTO]
proc TEI_Hermle_Takim_Listesi {} {
  global mom_output_file_directory mom_Op_No
  global mom_isv_tool_type mom_isv_tool_description mom_isv_tool_name
  global mom_isv_tool_diameter mom_isv_tool_point_angle

  set takim_lokasyonu "$mom_output_file_directory\\TAKIM_BILGILERI_OP$mom_Op_No.INI"
  file delete -force $takim_lokasyonu

  MOM_set_seq_off
  MOM_open_output_file $takim_lokasyonu
  TEI_lit \
    ";TOOL MAGAZIN ZEROPOINT,VERSION=1.01,TYPE=GC,TOOL=2,MAGAZIN=0,NPV=0,BNPV=0,CHAN=1 \"IS_METRIC\"=0 \"IS_OPT_DNO\"=0, IS_CREATE_TOOL_T_NR=0" \
    "INCH" \
    "CHANDATA(1)"

  set x [array size mom_isv_tool_type]
  for {set i 0} {$i < $x} {incr i} {
    set desc $mom_isv_tool_description($i)
    if {[string first "PROBE" $desc] >= 0} { continue }

    switch -glob -- $desc {
      "*TAP*" - "*KLAVUZ*" - "*PUNTA*"                    { set takim_tipi 240 }
      "*COUNTERBORE*" - "*DRILL*" - "*MATKAP*"            { set takim_tipi 200 }
      "*REAM*" - "*RAYBA*"                                { set takim_tipi 250 }
      "*CHAMFER*" - "*COUNTERSINK*" - "*MILL*" - "*FREZE*" - "*TESTERE*" -
      "*T_CUTTER*" - "*T-CUTTER*"                         { set takim_tipi 120 }
      default                                             { set takim_tipi 120 }
    }
    if {$mom_isv_tool_type($i) eq "Turning" || $mom_isv_tool_type($i) eq "Grooving"} {
      set takim_tipi 510
    }

    set n [expr {$i + 1}]
    TEI_lit \
      "\$TC_TP1\[$n\]=1" \
      "\$TC_TP2\[$n\]=\"[string range $mom_isv_tool_name($i) 0 30]\"" \
      "\$TC_TP3\[$n\]=1" \
      "\$TC_TP4\[$n\]=1" \
      "\$TC_TP5\[$n\]=1" \
      "\$TC_TP6\[$n\]=1" \
      "\$TC_TP7\[$n\]=1" \
      "\$TC_TP8\[$n\]=66" \
      "\$TC_TPC2\[$n\]=0" \
      "\$TC_TPC9\[$n\]=3.000" \
      "\$TC_TPC4\[$n\]=8.000" \
      "\$TC_TPC12\[$n\]=\"\"" \
      "\$TC_TPC13\[$n\]=\"\"" \
      "\$TC_DP1\[$n,1\]=$takim_tipi" \
      "\$TC_DP2\[$n,1\]=9.000" \
      "\$TC_DP3\[$n,1\]=0.000" \
      "\$TC_DP4\[$n,1\]=0.000" \
      "\$TC_DP5\[$n,1\]=0.000" \
      "\$TC_DP6\[$n,1\]=[format %4.4f [expr {$mom_isv_tool_diameter($i)/2}]]" \
      "\$TC_DP24\[$n,1\]=[format %4.4f $mom_isv_tool_point_angle($i)]" \
      "\$TC_DP25\[$n,1\]=256.000" \
      "\$TC_DPC1\[$n,1\]=0.0" \
      "\$TC_DPC7\[$n,1\]=1.000" \
      "\$TC_DPC8\[$n,1\]=0.050"
  }
  MOM_output_literal "M30"
  MOM_close_output_file $takim_lokasyonu
}
LIB_GE_command_buffer_edit_append LIB_GE_listing_info LISTING_INFO TEI_Hermle_Takim_Listesi TEI_Hermle_Takim_ListesiTAG


#==============================================================================
# 6  TOOL CHANGE + BLUM LASER
#==============================================================================
# Library tool change block is replaced by our own
LIB_GE_command_buffer_edit_remove MOM_tool_change_LIB TOOL_CHANGE_AUTO @TOOL_CHANGE_AUTO

# T / M6 / D                                                        [PHOTO]
#  - not for the items after the GRUP item (same tool, runs on)
#  - not after a GAGECUT or SHIMSET item (same tool continues)
proc Hermle_Custom_Toolchange {} {
  global mom_tool_name mom_sys_tool_change_code mom_tool_adjust_register
  global prev_op_gage prev_op_shimset takim_degistimi grup_flag TEI_c832

  if {$grup_flag} { return }
  if {![TEI_is_turn]} { MOM_do_template return_home_value_Z }
  if {$prev_op_gage == 0 && $prev_op_shimset == 0} {
    TEI_lit \
      "T=\"[string range $mom_tool_name 0 30]\"" \
      "M$mom_sys_tool_change_code" \
      "D$mom_tool_adjust_register"
    set takim_degistimi 1
    set TEI_c832 ""                 ;# new tool -> CYCLE832 again in the header
  }
}

# Tool change + BLUM laser check, at the MSYS event                 [PHOTO]
proc TEI_Hermle_Laser {} {
  global Item_No grup_flag prev_op_gage mom_blum_ON mom_operation_name
  global mom_tool_description mom_tool_diameter
  global mom_blum_E96 mom_blum_E97 mom_blum_E98 mom_blum_E217 mom_blum_E219

  Hermle_Custom_Toolchange
  if {[TEI_is_probe]}     { return 1 }
  if {$grup_flag}         { return 1 }
  if {$prev_op_gage == 1} { return 1 }
  if {[TEI_is_turn]}      { return 1 }

  TEI_extra_msg "ITEM BASI"

  if {![info exists mom_blum_ON] || $mom_blum_ON ne "ON"} { return 1 }

  TEI_lit " " \
    "TP${Item_No}LASER:" \
    "R80=2000 ;(* CUTTING TOOL SPEED *)" \
    "R91=0  ;(* TOOL PRE LENGTH *)"

  set blum "/_N_CUS_DIR/_N_TEI_BLUM_DIR"
  switch -glob -- $mom_tool_description {
    "*PUNTA*" - "*TAP*" - "*KLAVUZ*" - "*SPOTDRILL*" - "*FLEXHONE*" - "*TARAMA*" {
      TEI_lit \
        "R108=10.500 ;( TOOL LENGTH FOR CHECK )" \
        "R109=0.100 ;( TOOL LENGTH TOLERANCE)" \
        "R213=0 ;( TOOL PROB REVISION )" \
        "CALL\"$blum/_N_SUB2006_SPF\"" \
        "   "
    }
    "*COUNTERBORE*" - "*DRILL*" - "*MATKAP*" - "*REAM*" - "*RAYBA*" - "*MILL*" -
    "*FREZE*" - "*T-CUTTER*" - "*TESTERE*" - "*T_CUTTER*" {
      TEI_lit \
        "R92=([format %2.3f $mom_tool_diameter]/2) ;(* TOOL RADIUS *)" \
        "R97=[format %2.3f $mom_blum_E97] ;(*RADIUS POSITION FOR LENGTH MEASUREMENT*)" \
        ";(*-should be smaller from tool rad.*)" \
        "R96=[format %2.3f $mom_blum_E96] ;(*LENGTH FROM THE TOOL TIP*)" \
        ";(*-for Radius measurement*)" \
        "R98=[format %2.3f $mom_blum_E98] ;(*RADIUS TOLERANCE*)" \
        "R108=10.500 ;( TOOL LENGTH FOR CHECK )" \
        "R109=0.100 ;( TOOL LENGTH TOLERANCE)" \
        "R213=0 ;( TOOL PROB REVISION )"
      if {[string match "*FREZE*" $mom_tool_description]} {
        MOM_output_literal "CALL\"$blum/_N_SUB2000_SPF\""
      } else {
        MOM_output_literal "CALL\"$blum/_N_SUB2001_SPF\""
      }
      MOM_output_literal "   "
    }
    "*CHAMFER*" - "*COUNTERSINK*" {
      if {![info exists mom_blum_E217]} {
        TEI_abort "E217 Chamfer Takim Acisi tanimli degil," \
                  "'BLUM LASER PARAMETRELERI'  UDE'si guncel degildir,  Lutfen guncelleyiniz!!"
      }
      TEI_lit \
        "R92=([format %2.3f $mom_tool_diameter]/2) ;(* TOOL RADIUS *)" \
        ";(*-should be smaller from tool rad.*)" \
        "R217=($mom_blum_E217) ;(* CHAMFER TOOL ANGLE *)" \
        "R214=(0.040/2) ;(* RAD VALUE CALCULATION FOR SECOND LENGTH MEASUREMENT *)" \
        "R219=($mom_blum_E219) ;(* ANGLE TOLERANCE *)"
      if {[string match "*BACKCHAMFER*" $mom_tool_description] || \
          [string match "*BACK CHAMFER*" $mom_tool_description] || \
          [string match "*BACKCOUNTERSINK*" $mom_tool_description]} {
        MOM_output_literal "CALL\"$blum/_N_SUB20033_SPF\""
      } else {
        MOM_output_literal "CALL\"$blum/_N_SUB20022_SPF\""
      }
      MOM_output_literal "   "
    }
    default {
      TEI_abort "Lutfen Takim Hazirlama penceresinde  TAKIM  ISMINI  kontrol edin!!" \
                "Ilgili Item:    ( $mom_operation_name )"
    }
  }
  TEI_lit $::TEI_HOME "M01" "ENDLABEL:" " "
}
LIB_GE_command_buffer_edit_prepend MOM_msys MOM_msys_LIB_ENTRY_start TEI_Hermle_Laser TEI_Hermle_LaserTAG

# Spindle end                                     [PHOTO, rest of it TODO]
proc TEI_spindle_end {} {
  if {[TEI_is_probe]} { return 1 }
  Reset_axis_to_zero
  TEI_todo "rest of SPINDLE_END buffer"
}
LIB_GE_command_buffer_edit_append LIB_SPINDLE_end SPINDLE_END TEI_spindle_end TEI_spindle_endTAG

proc Reset_axis_to_zero {} { TEI_todo Reset_axis_to_zero }              ;# [TODO]
# [TODO] TEI_tool_change_tracker (old line 434) - not seen
# [TODO] your MILL safety routine - not seen


#==============================================================================
# 7  ITEM START HEADER  (first move / initial move, once per item)  [PHOTO]
#  [VERIFY] proc name: the photo of this proc had no name line. Your buffer
#  edits call Hermle_Ilk_Hareket at the first/initial move, and this proc
#  has the Baslik_basildi guard for exactly that, so I named it that way.
#  CYCLE832 now comes right before the TP label (outside the akilli loop),
#  for every milling item; continuation items of a GRUP get no label.
#==============================================================================
proc Hermle_Ilk_Hareket {} {
  global Baslik_basildi Item_No grup_flag
  if {[TEI_is_turn]}  { return 0 }
  if {$Baslik_basildi} { return 0 }
  if {[TEI_is_probe]} { return 1 }
  set Baslik_basildi 1

  if {!$grup_flag} {
    MOM_output_literal " "
    TEI_c832_on
    set label [expr {[TEI_is_gage] ? "GAGECUT_TP$Item_No" : "TP$Item_No"}]
    TEI_lit "$label: ;******* KESIM BASLANGICI *******" "STOPRE"
    if {[TEI_akilli_on]} { MOM_output_literal "R20=R5*R10" }
    MOM_output_literal " "
  } else {
    TEI_c832_on                     ;# no output if it is still on
  }
  Hermle_Custom_Cycle800
  Hermle_Sub_Start
}
LIB_GE_command_buffer_edit_append  MOM_first_move_LIB MOM_first_move_LIB_ENTRY_start Hermle_Ilk_Hareket Hermle_Ilk_Harekettag
LIB_GE_command_buffer_edit_prepend MOM_initial_move   MOM_initial_move_ENTRY_start   Hermle_Ilk_Hareket Hermle_Ilk_Harekettag

# Sub program files                                                 [TODO]
#  Old end code closed the sub file when the last 3 characters of the
#  operation name matched something (cut off in the photo).
proc Hermle_Sub_Start {} { TEI_todo Hermle_Sub_Start }
proc TEI_sub_is_end {} { return 0 }                                    ;# [TODO]
proc TEI_sub_close {} {
  global sub_loc mom_output_file_full_name
  if {$sub_loc eq "" || ![TEI_sub_is_end]} { return }
  MOM_output_literal "M17"
  MOM_set_seq_on
  MOM_close_output_file $sub_loc
  MOM_open_output_file $mom_output_file_full_name
  set sub_loc ""
}
# [TODO] old buffer around old line 227: first linear move with
#        "set ilk_hareket 1" - not fully seen


#==============================================================================
# 8  CYCLE800                                                       [PHOTO]
#==============================================================================
proc Hermle_Custom_Cycle800 {} {
  global cycle800_flag mom_out_angle_pos mom_operation_name mom_MAHO_multiaxis_control
  if {[TEI_is_probe]}    { return 1 }
  if {$cycle800_flag}    { return 1 }

  set a [format_decimal_string $mom_out_angle_pos(0) {4.4}]
  set c [format_decimal_string $mom_out_angle_pos(1) {4.4}]

  if {[info exists mom_MAHO_multiaxis_control] && $mom_MAHO_multiaxis_control eq "G7SIZ"} {
    if {$a != 0} {
      TEI_abort "$mom_operation_name operasyonunda G7siz harekette A ekseni 0 degil. G7 ye cevir"
    }
    MOM_output_literal "G0 A$a C=($c+R20)"
    set cycle800_flag 1
    return 1
  }
  set mode [expr {[TEI_akilli_on] ? 0 : 1}]
  MOM_output_literal "CYCLE800($mode,\"HERMLE\",100000,192,0,0,0,$a,($c+R20),0,0,0,-1,,100)"
  set cycle800_flag 1
}
LIB_GE_command_buffer_edit_replace LIB_CSYS_plane_output_init CYCLE800 @CYCLE800 Hermle_Custom_Cycle800 Hermle_Custom_Cycle800TAG


#==============================================================================
# 9  CYCLE832 STATE + TAPPING
#  One owner for CYCLE832: TEI_c832_on / TEI_c832_off write it only when the
#  state changes. Tapping: off right before the tap cycle (MCALL CYCLE84),
#  back on right after the cycle is cancelled (still inside the item, so it
#  stays inside an akilli loop or a GRUP).
#==============================================================================
proc TEI_c832_on {} {
  global TEI_c832 TEI_CYCLE832_ON
  if {[TEI_is_turn] || $TEI_c832 eq "on"} { return }
  MOM_output_literal $TEI_CYCLE832_ON
  set TEI_c832 on
}
proc TEI_c832_off {} {
  global TEI_c832 TEI_CYCLE832_OFF
  if {$TEI_c832 eq "off"} { return }
  MOM_output_literal $TEI_CYCLE832_OFF
  set TEI_c832 off
}

proc TAP_hsm_off {} {
  global tap_hsm_done tap_hsm_was_on TEI_c832
  if {$tap_hsm_done} { return }
  set tap_hsm_was_on [expr {$TEI_c832 eq "on"}]
  TEI_c832_off
  set tap_hsm_done 1
}
proc TAP_hsm_restore {} {
  global tap_hsm_done tap_hsm_was_on tap_hsm_restored
  if {!$tap_hsm_done || $tap_hsm_restored} { return }
  set tap_hsm_restored 1
  if {$tap_hsm_was_on} { TEI_c832_on }
}

foreach ev $TAP_EVENTS {
  if {[llength [info commands $ev]] && ![llength [info commands TAP_orig_$ev]]} {
    rename $ev TAP_orig_$ev
    proc $ev {} [format { TAP_hsm_off ; uplevel 1 TAP_orig_%s } $ev]
  }
}
if {[llength [info commands MOM_cycle_off]] && ![llength [info commands TAP_orig_MOM_cycle_off]]} {
  rename MOM_cycle_off TAP_orig_MOM_cycle_off
  proc MOM_cycle_off {} { uplevel 1 TAP_orig_MOM_cycle_off ; TAP_hsm_restore }
}


#==============================================================================
# 10  AKILLI  (pattern loop: R5 counter, R10 angle step, R20 = R5*R10)
#  Not allowed together with GRUP (section 12).
#==============================================================================
# UDE handler: counter init                                        [VERIFY]
#  Rebuilt from the posted output (R5 = 0 ; SAYAC / R10 = (360.000/n) / M01).
#  Paste your old MOM_tei_akilli_prg body if it did more.
#  Not after a GAGECUT item: the item after GAGECUT shares its loop, an
#  R5=0 there would sit inside the loop and it would never end.
proc MOM_tei_akilli_prg {} {
  global mom_delik_sayisi prev_op_gage
  if {[TEI_akilli_on] && $prev_op_gage == 0} {
    TEI_lit "R5 = 0 ; SAYAC" "R10 = (360.000/$mom_delik_sayisi)" "M01"
  }
  CUS_akilli_select "UDE"
}

# Loop end at the end of the item                                   [PHOTO]
proc AKILLI_loop_end {} {
  global Item_No mom_delik_sayisi mom_delik_kontrol prev_op_gage TEI_HOME
  TEI_lit " " "STOPRE" "R5=R5+1" " " \
    "IF R5 == $mom_delik_sayisi; **** BITIS ****" \
    "GOTO ITEM${Item_No}FINISH" \
    "ENDIF" " " \
    "IF (R5 MOD $mom_delik_kontrol) == 1 ; **** KONTROL ****"
  MOM_do_template return_home_value_Z
  TEI_m9_m5
  TEI_extra_msg "AKILLI MESAJI"
  TEI_lit "M01" "ENDIF; **** KONTROL SONU ****"

  if {$mom_delik_kontrol < $mom_delik_sayisi} {
    TEI_lit " " \
      "IF (R5 MOD $mom_delik_kontrol) == 0 ; **** TAKIM DEGISIMI ****" \
      $TEI_HOME \
      "MSG(\"TAKIM DEGISTIR\")" \
      "DR" \
      "M1" \
      "REPEAT TP${Item_No}LASER"
    if {$prev_op_gage == 1} { MOM_output_literal "GOTO GAGECUT_TP$Item_No" }
    MOM_output_literal "ENDIF; **** TAKIM DEGISIM SONU ****"
  }
  TEI_lit "GOTOB TP$Item_No" " " "ITEM${Item_No}FINISH: ; **** ITEM SONU ****"
  MOM_do_template return_home_value_Z
}


#==============================================================================
# 11  END OF ITEM                                                   [PHOTO]
#==============================================================================
proc TEI_end_of_item {} {
  global grup_devam
  if {[TEI_is_probe]} { return }          ;# probe items: PROBE_end

  TEI_sub_close

  # Same tool next, no akilli, not inside a group: stop here
  if {[TEI_next_tool_change] eq "NO" && ![TEI_akilli_on] && !$grup_devam} {
    MOM_do_template return_home_value_Z
    TEI_m9_m5
    TEI_extra_msg "AKILLI MESAJI"
    TEI_extra_msg "ITEM SONU"
  }

  # GAGECUT: no loop end - the next item closes the loop (GAGECUT_TP label)
  if {[TEI_is_gage]} { return }

  if {[TEI_akilli_on]} { AKILLI_loop_end }
}

# L_M54 at the end of every item
#  not after SHIMSET (tool stays at the part), not between GRUP items,
#  not after probe items (PROBE_end does it before the next tool)
proc TEI_home {} {
  global shimset_mode grup_devam TEI_HOME
  if {$shimset_mode || $grup_devam || [TEI_is_probe]} { return }
  MOM_output_literal $TEI_HOME
}

# Item bookkeeping                                                 [VERIFY]
#  Item_No +1 after every item, except:
#   - GAGECUT: shares its TP number with the next item (GAGECUT_TP1 / TP1)
#   - GRUP items before GRUPSON: the whole group is one item
proc TEI_item_done {} {
  global Item_No prev_op_gage prev_op_shimset grup_devam TEI_KEY_SHIMSET
  set gage [TEI_is_gage]
  if {!$gage && !$grup_devam} { incr Item_No }
  set prev_op_gage    $gage
  set prev_op_shimset [TEI_name_has $TEI_KEY_SHIMSET]
}


#==============================================================================
# 12  GRUP / GRUPSON                                                [OK]
#  Operation name with GRUP opens a group, the next one with GRUPSON closes
#  it (both belong to it). Inside: one tool, no tool change, no laser, no
#  M9 M5 / home / L_M54 between items, the following items get their name
#  as a comment. GRUPSON ends like a normal item.
#   grup_flag   1 = item after the GRUP item
#   grup_devam  1 = group continues after this item
#   grup_icinde 1 = item is part of a group
#==============================================================================
set grup_open     0
set grup_flag     0
set grup_devam    0
set grup_icinde   0
set grup_start_op ""

proc GRUP_start {} {
  global mom_operation_name TEI_KEY_GRUP TEI_KEY_GRUPSON
  global grup_open grup_flag grup_devam grup_icinde grup_start_op
  set is_end   [TEI_name_has $TEI_KEY_GRUPSON]
  set is_begin [expr {!$is_end && [TEI_name_has $TEI_KEY_GRUP]}]

  if {$is_end && !$grup_open} {
    TEI_abort "GRUPSON var ama acik GRUP yok." "Ilgili Item:    ( $mom_operation_name )"
  }
  if {$is_begin && $grup_open} {
    TEI_abort "GRUP ( $grup_start_op ) GRUPSON ile kapanmadan yeni GRUP basladi." \
              "Ilgili Item:    ( $mom_operation_name )"
  }
  if {$is_begin} {
    set grup_open 1
    set grup_start_op $mom_operation_name
  }
  set grup_icinde $grup_open
  set grup_flag   [expr {$grup_open && !$is_begin}]
  set grup_devam  [expr {$grup_open && !$is_end}]
}

proc GRUP_start_post {} {
  global grup_flag mom_operation_name
  if {$grup_flag} { MOM_output_literal ";*** $mom_operation_name ***" }
}

proc GRUP_end_check {} {
  global grup_icinde grup_devam grup_start_op mom_operation_name
  if {!$grup_icinde || !$grup_devam} { return }
  if {[info exists ::mom_operation_name_list] && [get_next_oper_name] eq ""} {
    TEI_abort "GRUP ( $grup_start_op ) GRUPSON ile kapanmamis." "Son Item:    ( $mom_operation_name )"
  }
  if {[TEI_next_tool_change] eq "YES"} {
    TEI_abort "GRUP icinde takim degisemez.  GRUP: ( $grup_start_op )" \
              "Ilgili Item:    ( [get_next_oper_name] )"
  }
}

proc GRUP_end_done {} {
  global grup_icinde grup_devam grup_open
  if {$grup_icinde && !$grup_devam} { set grup_open 0 }
}

# Akilli inside a group -> stop the moment the UDE sets it
proc GRUP_akilli_trace {args} {
  global grup_icinde mom_operation_name
  if {![info exists grup_icinde] || !$grup_icinde} { return }
  if {[CUS_akilli_on]} {
    TEI_abort "GRUP ve AKILLI ayni anda kullanilamaz." "Ilgili Item:    ( $mom_operation_name )"
  }
}
trace add variable ::mom_akilli write GRUP_akilli_trace


#==============================================================================
# 13  TURN                                                          [OK]
#  SHIMSET items: no spindle start, no coolant on, no G96/G961, G94 feed
#  Other TURN items: spindle + coolant on at start, off + G94 at the end
#  First move F200, safety lines at the start of every TURN item
#==============================================================================
set shimset_mode     0
set shimset_turn_op  0
set shimset_g94_done 0

proc SHIMSET_start {} {
  global shimset_mode shimset_turn_op shimset_g94_done TEI_KEY_SHIMSET
  set shimset_g94_done 0
  TURN_first_move_restore
  GAGE_reset
  set shimset_turn_op [TEI_is_turn]
  set shimset_mode [expr {$shimset_turn_op && [TEI_name_has $TEI_KEY_SHIMSET]}]
}

proc SHIMSET_block_words {} {
  global shimset_mode shimset_turn_op SHIMSET_BLOCK_ADDRS
  if {$shimset_mode} {
    # M_coolant: no M8   G_spin: no G96/G961 (feed per rev without spindle)
    foreach a $SHIMSET_BLOCK_ADDRS { catch {MOM_disable_address $a} }
  } elseif {$shimset_turn_op} {
    MOM_force once M_coolant        ;# M8 on the first coolant call
    MOM_force once G_spin           ;# literal G94 bypassed modal tracking
  }
}

proc SHIMSET_unblock_words {} {
  global shimset_mode SHIMSET_BLOCK_ADDRS
  if {!$shimset_mode} { return }
  foreach a $SHIMSET_BLOCK_ADDRS { catch {MOM_enable_address $a} }
}

proc TURN_safety_lines {} {
  global shimset_turn_op TURN_SAFETY_LINES
  if {!$shimset_turn_op} { return }
  foreach l $TURN_SAFETY_LINES { MOM_output_literal $l }
}

proc STOP_spindle_coolant {} {
  SHIMSET_orig_LIB_WRITE_coolant off
  LIB_SPINDLE_end
  MOM_output_literal "G94"          ;# spindle stopped: leave feed per rev
}

# Cutting TURN items, same tool next, not inside a group
proc TURN_stop_spindle {} {
  global shimset_turn_op shimset_mode grup_devam
  if {$shimset_turn_op && !$shimset_mode && [TEI_next_tool_change] eq "NO" && !$grup_devam} {
    STOP_spindle_coolant
  }
}

if {![llength [info commands SHIMSET_orig_LIB_SPINDLE_start]]} {
  rename LIB_SPINDLE_start SHIMSET_orig_LIB_SPINDLE_start
  proc LIB_SPINDLE_start {args} {
    global shimset_mode shimset_g94_done
    if {$shimset_mode} {
      if {!$shimset_g94_done} {     ;# called on every move -> G94 once
        MOM_output_literal "G94"
        set shimset_g94_done 1
      }
      return
    }
    return [uplevel 1 [list SHIMSET_orig_LIB_SPINDLE_start {*}$args]]
  }
}

if {![llength [info commands SHIMSET_orig_LIB_WRITE_coolant]]} {
  rename LIB_WRITE_coolant SHIMSET_orig_LIB_WRITE_coolant
  proc LIB_WRITE_coolant {args} {
    global shimset_mode
    TURN_first_move_restore         ;# undo F200 right after the first move
    if {$shimset_mode && [lindex $args 0] eq "on"} { return }
    return [uplevel 1 [list SHIMSET_orig_LIB_WRITE_coolant {*}$args]]
  }
}

# First move F200: every global "*feed*" holding the first move's feed is
# set to 200 and restored right after the move (COOLANT_ON step)
set turn_feed_saved {}

proc TURN_first_move_feed {} {
  global mom_feed_rate TURN_FIRST_MOVE_FEED TURN_FEED_DEBUG turn_feed_saved
  TURN_first_move_restore
  if {![info exists mom_feed_rate] || ![string is double -strict $mom_feed_rate]} { return }
  set orig $mom_feed_rate
  set changed {}
  foreach v [info globals] {
    if {![string match -nocase "*feed*" $v]} { continue }
    upvar #0 $v val
    if {[array exists val] || ![info exists val]} { continue }
    if {![string is double -strict $val]} { continue }
    if {abs($val - $orig) > 1e-6} { continue }
    dict set turn_feed_saved $v $val
    set val $TURN_FIRST_MOVE_FEED
    lappend changed $v
  }
  MOM_force once F
  if {$TURN_FEED_DEBUG} { MOM_output_literal ";F200 DEBUG orig=$orig vars=$changed" }
}

proc TURN_first_move_restore {} {
  global turn_feed_saved
  if {![info exists turn_feed_saved]} { return }
  dict for {v val} $turn_feed_saved { upvar #0 $v ref ; set ref $val }
  set turn_feed_saved {}
}

# TURN first move: initial_move_turn template, CYCLE800_turn, Y forced, F200
LIB_GE_command_buffer_edit_replace MOM_linear_move_LIB FIRST_MOVE_TURN @MOVE {
    MOM_do_template initial_move_turn
} _customfirstmove
LIB_GE_command_buffer_edit_prepend MOM_linear_move_LIB FIRST_MOVE_TURN {
    MOM_do_template CYCLE800_turn
    MOM_force once Y
    TURN_first_move_feed
} _outputopstop


#==============================================================================
# 14  GAGECUT WEAR OFFSET CHECK                                     [OK]
#  End of GAGECUT items (not SHIMSET): loop until the wear value is inside
#  the tolerance.  TURN: X / Z, tolerances from the torna_offset_kontrol UDE
#                   MILL: radius / Z, fixed 0.004
#==============================================================================
set gage_label_count 0

proc GAGE_reset {} { set ::gage_tol_x "" ; set ::gage_tol_z "" }
GAGE_reset

proc GAGE_capture_ude {} {
  global gage_tol_x gage_tol_z mom_t_offset_kontrol_tolerans_X mom_t_offset_kontrol_tolerans_Z
  if {[info exists mom_t_offset_kontrol_tolerans_X]} { set gage_tol_x $mom_t_offset_kontrol_tolerans_X }
  if {[info exists mom_t_offset_kontrol_tolerans_Z]} { set gage_tol_z $mom_t_offset_kontrol_tolerans_Z }
}

if {[llength [info commands MOM_torna_offset_kontrol]]} {
  if {![llength [info commands GAGE_orig_torna_offset_kontrol]]} {
    rename MOM_torna_offset_kontrol GAGE_orig_torna_offset_kontrol
    proc MOM_torna_offset_kontrol {} { GAGE_capture_ude ; GAGE_orig_torna_offset_kontrol }
  }
} else {
  proc MOM_torna_offset_kontrol {} { GAGE_capture_ude }
}

proc GAGE_output_axis {axis dp tol} {
  global gage_label_count
  if {$tol eq "" || ![string is double -strict $tol] || $tol <= 0.0} { return }
  incr gage_label_count
  set lbl  "WCHK${axis}${gage_label_count}"
  set t    [format "%.4f" $tol]
  set var  [format {$TC_DP%d[$P_TOOLNO,$P_TOOL]} $dp]
  set name [expr {$axis eq "R" ? "RADYUS" : $axis}]
  TEI_lit "${lbl}:" \
          "IF ABS(${var})>${t}" \
          "MSG(\"${name} ASINMA OFSETI TOLERANS DISI (+/-${t}) - KONTROL ET\")" \
          "M0" \
          "GOTOB ${lbl}" \
          "ENDIF"
}

proc GAGE_end {} {
  global gage_tol_x gage_tol_z GAGE_WEAR_DP_X GAGE_WEAR_DP_Z
  global GAGE_MILL_DP_Z GAGE_MILL_DP_R GAGE_MILL_TOL_R GAGE_MILL_TOL_Z shimset_turn_op
  if {![TEI_is_gage]} { return }
  if {!$shimset_turn_op} {
    TEI_lit "M01" ";*** ASINMA OFSET TOLERANS KONTROLU ***"
    GAGE_output_axis R $GAGE_MILL_DP_R $GAGE_MILL_TOL_R
    GAGE_output_axis Z $GAGE_MILL_DP_Z $GAGE_MILL_TOL_Z
  } else {
    if {$gage_tol_x eq "" && $gage_tol_z eq ""} { return }
    TEI_lit "M01" ";*** ASINMA OFSET TOLERANS KONTROLU ***"
    GAGE_output_axis X $GAGE_WEAR_DP_X $gage_tol_x
    GAGE_output_axis Z $GAGE_WEAR_DP_Z $gage_tol_z
  }
  MOM_output_literal "MSG()"
}


#==============================================================================
# 15  INSERT TEXT -> 840D                                           [OK]
#   M00(MSG,text) / M01(MSG,text)  ->  MSG("text") / M0|M1 / MSG()
#   (MSG,text)                     ->  MSG("text")
#   (comment)                      ->  ; comment
#==============================================================================
proc INSERT_to_840d {txt} {
  set t [string trim $txt]
  if {[regexp -nocase {^M0*([01])\s*\(\s*MSG\s*,\s*(.*)\)\s*$} $t -> mcode msg]} {
    set msg [string map {"\"" "'"} [string trim $msg]]
    return [list "MSG(\"$msg\")" "M$mcode" "MSG()"]
  }
  if {[regexp -nocase {^\(\s*MSG\s*,\s*(.*)\)\s*$} $t -> msg]} {
    set msg [string map {"\"" "'"} [string trim $msg]]
    return [list "MSG(\"$msg\")"]
  }
  if {[regexp {^\((.*)\)$} $t -> cmt]} { return [list "; [string trim $cmt]"] }
  return {}
}

if {[llength [info commands MOM_insert]] && ![llength [info commands INSERT_orig_MOM_insert]]} {
  rename MOM_insert INSERT_orig_MOM_insert
  proc MOM_insert {} {
    global mom_Instruction
    if {[info exists mom_Instruction]} {
      set lines [INSERT_to_840d $mom_Instruction]
      if {[llength $lines]} { foreach l $lines { MOM_output_literal $l } ; return }
    }
    INSERT_orig_MOM_insert
  }
}


#==============================================================================
# 16  COOLANT / SPINDLE DIRECTION RESET                             [OK]
#  A coolant UDE changes mom_coolant_* for all later operations: snapshot at
#  the first start of path, restored at the end of every item.
#  Spindle direction back to CLW at the end of every item.
#==============================================================================
set COOL_snapshot ""

proc COOL_vars {} {
  set out {}
  foreach v [info globals mom_coolant*] {
    if {[array exists ::$v]} { continue }
    lappend out $v
  }
  return $out
}
proc COOL_take_snapshot {} {
  global COOL_snapshot
  if {$COOL_snapshot ne ""} { return }
  set COOL_snapshot [dict create __taken 1]
  foreach v [COOL_vars] { dict set COOL_snapshot $v [set ::$v] }
}
proc COOL_restore {} {
  global COOL_snapshot
  if {$COOL_snapshot eq ""} { return }
  foreach v [COOL_vars] {
    if {[dict exists $COOL_snapshot $v]} {
      set ::$v [dict get $COOL_snapshot $v]
    } else {
      unset -nocomplain ::$v
    }
  }
}
proc SPINDLE_reset {} { set ::mom_spindle_direction $::SPINDLE_DEFAULT_DIR }


#==============================================================================
# 17  PROBE                                                         [PHOTO]
#  Probe items write no motion of their own: the main file is closed at the
#  start [VERIFY], the probe events collect the points, and at the end the
#  file is reopened and the TEI cycles are written.
#   PP7000 in the name -> TEIFLAT     PP7300 -> TEICENTER     C0 -> TEICLOCK
#==============================================================================
proc PROBE_start {} {
  global PROBE_CLOSE_OUTPUT mom_output_file_full_name counter
  if {![TEI_is_probe]} { return }
  set counter 1                                                    ;# [VERIFY]
  if {$PROBE_CLOSE_OUTPUT} {
    MOM_set_seq_off
    MOM_close_output_file $mom_output_file_full_name
    set ::PROBE_file_closed 1
  }
}

# Approach point of the probe item (first/initial move)            [VERIFY]
proc PROBE_capture_initial {} {
  global initial_move_points mom_pos
  if {![TEI_is_probe] || [info exists initial_move_points(0)]} { return }
  foreach i {0 1 2} { set initial_move_points($i) $mom_pos($i) }
}
LIB_GE_command_buffer_edit_prepend MOM_initial_move   MOM_initial_move_ENTRY_start   PROBE_capture_initial PROBE_capture_initialtag
LIB_GE_command_buffer_edit_append  MOM_first_move_LIB MOM_first_move_LIB_ENTRY_start PROBE_capture_initial PROBE_capture_initialtag

proc PROBE_save_point {} {
  global probe_noktalariX probe_noktalariY probe_noktalariZ
  global probe_noktalariXprev probe_noktalariYprev probe_noktalariZprev mom_pos mom_prev_pos
  set probe_noktalariX(0) $mom_pos(0)
  set probe_noktalariY(0) $mom_pos(1)
  set probe_noktalariZ(0) $mom_pos(2)
  set probe_noktalariXprev(0) $mom_prev_pos(0)
  set probe_noktalariYprev(0) $mom_prev_pos(1)
  set probe_noktalariZprev(0) $mom_prev_pos(2)
}

proc Hermle_Probe_Point {} {
  global probe_probing_routine mom_pos mom_prev_pos counter
  PROBE_save_point
  append probe_probing_routine \
    "GETPOINT([format %4.4f $mom_pos(0)],[format %4.4f $mom_pos(1)],[format %4.4f $mom_pos(2)],$counter)\n" \
    "G01 X[format %4.4f $mom_prev_pos(0)] Y[format %4.4f $mom_prev_pos(1)] Z[format %4.4f $mom_prev_pos(2)]\n"
  incr counter
}
LIB_GE_command_buffer_edit_append MOM_probe_angled_surface_point MOM_probe_angled_surface_point_ENTRY_start Hermle_Probe_Point Hermle_Probe_Pointtag

proc Hermle_Probe_Bore {} {
  global probe_probing_routine mom_pos counter mom_probing_diameter
  PROBE_save_point
  set clock [TEI_name_has "C0"]
  set r [expr {$mom_probing_diameter / 2.0}]
  set x [format %4.4f $mom_pos(0)]
  set y [format %4.4f $mom_pos(1)]
  set z [format %4.4f $mom_pos(2)]
  if {abs($mom_pos(0)) > abs($mom_pos(1))} {
    set p1 [list $x [format %4.4f [expr {$mom_pos(1) + $r}]]]
    set p2 [list $x [format %4.4f [expr {$mom_pos(1) - $r}]]]
  } else {
    set p1 [list [format %4.4f [expr {$mom_pos(0) + $r}]] $y]
    set p2 [list [format %4.4f [expr {$mom_pos(0) - $r}]] $y]
  }
  foreach p [list $p1 $p2] sp {0 180} {
    if {$clock} { append probe_probing_routine "SPOSA=AC($sp)\n" }
    append probe_probing_routine "GETPOINT([lindex $p 0],[lindex $p 1],$z,$counter)\n"
    incr counter
    append probe_probing_routine "G01 X$x Y$y F200\n"
  }
}
LIB_GE_command_buffer_edit_append MOM_probe_four_point_bore MOM_probe_four_point_bore_ENTRY_start Hermle_Probe_Bore Hermle_Probe_Bore

proc PROBE_cycle800_approach {} {
  global mom_out_angle_pos initial_move_points
  TEI_lit \
    "CYCLE800(1,\"HERMLE\",100000,192,0,0,0,[format %4.3f $mom_out_angle_pos(0)],([format %4.3f $mom_out_angle_pos(1)]+ R20),0,0,0,-1,,100)" \
    "G01 X[format %4.4f $initial_move_points(0)] Y[format %4.4f $initial_move_points(1)] F200. " \
    "G01 Z[format %4.4f $initial_move_points(2)]"
}

proc PROBE_end {} {
  global mom_output_file_full_name mom_output_file_directory mom_Op_No
  global mom_fixture_offset_value mom_sys_tool_change_code mom_tool_adjust_register
  global mom_pp7000_e19 mom_pp7300_e19 mom_probe_standoff_distance mom_out_angle_pos
  global probe_flag probe_loc probe_probing_routine initial_move_points TEI_HOME
  global probe_noktalariX probe_noktalariY probe_noktalariZ
  global probe_noktalariXprev probe_noktalariYprev

  if {![TEI_is_probe]} { return }

  if {$mom_fixture_offset_value < 5} {
    set g [expr {$mom_fixture_offset_value + 53}]
  } else {
    set g [expr {$mom_fixture_offset_value + 500}]
  }
  set f   $mom_fixture_offset_value
  set sd  [format %4.3f $mom_probe_standoff_distance]

  MOM_open_output_file $mom_output_file_full_name
  MOM_set_seq_on
  set ::PROBE_file_closed 0

  TEI_Antet                         ;# only if no item came before
  TEI_Item_Aciklama
  if {$probe_flag == 0} {
    MOM_do_template return_home_value_Z
    TEI_lit "T=\"PROBE\"" "M$mom_sys_tool_change_code" "D$mom_tool_adjust_register" \
            "G$g ; AKTIF OFFSET" "G17" "R5 = 0" "R10 = 0" "R20 = 0" $TEI_HOME "M446; PROBE AC"
    set probe_flag 1
  }

  if {[TEI_name_has "PP7000"]} {
    PROBE_cycle800_approach
    MOM_output_literal "TEIFLAT($f,$mom_pp7000_e19,[format %4.4f $probe_noktalariX(0)],[format %4.4f $probe_noktalariY(0)],[format %4.4f $initial_move_points(2)],$sd,0.005,0.0005)"
    MOM_do_template return_home_value_Z
    Reset_axis_to_zero
  }

  if {[TEI_name_has "PP7300"]} {
    set cap      [expr {hypot($probe_noktalariX(0), $probe_noktalariY(0))}]
    set cap_appr [expr {hypot($probe_noktalariXprev(0), $probe_noktalariYprev(0))}]
    set ic_dis   [expr {$cap > $cap_appr ? 0 : 1}]
    PROBE_cycle800_approach
    MOM_output_literal "TEICENTER($f,$mom_pp7300_e19,[format %4.4f $probe_noktalariX(0)],[format %4.4f $probe_noktalariY(0)],[format %4.4f $probe_noktalariZ(0)],[format %4.4f $initial_move_points(2)],$ic_dis,$sd,0.005,0.0005,0)"
    MOM_do_template return_home_value_Z
    Reset_axis_to_zero
  }

  if {[TEI_name_has "C0"]} {
    set probe_loc "$mom_output_file_directory\\CLOCK_OP$mom_Op_No.SPF"
    file delete -force $probe_loc
    MOM_set_seq_off
    MOM_close_output_file $mom_output_file_full_name
    MOM_open_output_file $probe_loc
    MOM_output_literal $probe_probing_routine
    MOM_output_literal "M17"
    MOM_close_output_file $probe_loc
    MOM_open_output_file $mom_output_file_full_name
    MOM_set_seq_on

    set a1 [expr {$mom_out_angle_pos(0) != 0 ? 1 : 0}]
    TEI_lit "CLOCK_OP$mom_Op_No" "TEICLOCK($f,$a1,3)" "G$g" \
            "CLOCK_OP$mom_Op_No" "TEICLOCK($f,$a1,3)"
    MOM_do_template return_home_value_Z
    Reset_axis_to_zero
    set probe_loc ""
  }

  if {[TEI_next_tool_change] eq "YES"} {
    TEI_lit $TEI_HOME "M447; PROBE KAPA"
    Reset_axis_to_zero
    set probe_flag 0                                               ;# [VERIFY]
  }
  set probe_probing_routine ""
}


#==============================================================================
# 18  LOCK AXIS + AKILLI C ENGINE   (v21, logic unchanged)          [OK]
#  Start / end of path and the akilli UDE are no longer wrapped here - they
#  run from the flow table (section 4) and MOM_tei_akilli_prg (section 10)
#  through LOCK_start_pre / LOCK_start_post / LOCK_frames_off / LOCK_end_post.
#
#  Akilli: C=DC(<value>+R20) via the address fifth_axis_akilli; under TRAORI
#  AROT Z=R20 after TRAORI, ROT before TRAFOOF / CYCLE800 / end of the item.
#
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
#==============================================================================

set CUS_debug 0

# ------------------------------------------------------------- configuration
set CUS_lock_side    -1      ;# free-axis side: -1 = Y- with X locked (X- with Y locked)
set CUS_c_cw          1      ;# 1: C+ turns the table clockwise (C52); 0: counter-clockwise
set CUS_c_offset     ""      ;# added inside DC(), e.g. "+R20"; keep "" unless needed
set CUS_akilli_offset "+R20" ;# added inside DC() when mom_akilli = 1 (Lock Axis word;
                             ;#  the normal C word gets it from the address TRAILER)
set CUS_akilli_event  MOM_tei_akilli_prg ;# UDE that sets mom_akilli (handler: section 10)
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

# Item flow hooks (called from TEI_FLOW, section 4)
# start_pre: clean state (start-event UDEs fire after start of path)
proc LOCK_start_pre {} {
    global CUS_lock CUS_last_c
    array unset ::CUS_warned
    array set ::CUS_warned {}
    if {$CUS_lock(active)} { CUS_lock_end "start of path" }
    set CUS_last_c ""
    CUS_rot_off "start of path"
}
proc LOCK_start_post {} { CUS_akilli_select "start of path" }

# end_post, first: ROT before anything else of the item end, so the akilli
# loop end (GOTOB TP) never jumps back with AROT still active
proc LOCK_frames_off {} { CUS_rot_off "end of path" }

# end_post, after the akilli loop end: C back, lock off, akilli off
proc LOCK_end_post {} {
    global CUS_last_c
    CUS_restore_c
    CUS_lock_end "end of path"
    set CUS_last_c ""
    if {$::CUS_akilli_per_op} {
        set ::mom_akilli 0            ;# trace switches back to the normal C address
        CUS_akilli_select "end of path"
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

#==============================================================================
# DEBUG GUARD on the procs the library calls from its buffers / events
# (only when TEI_DEBUG = 1; same reporting as the flow steps)
#==============================================================================
if {$TEI_DEBUG} {
  foreach p {TEI_Antet TEI_Hermle_Takim_Listesi TEI_Hermle_Laser TEI_spindle_end
             Hermle_Ilk_Hareket Hermle_Custom_Cycle800 MOM_tei_akilli_prg
             PROBE_capture_initial Hermle_Probe_Point Hermle_Probe_Bore
             TURN_first_move_feed MOM_torna_offset_kontrol} {
    if {![llength [info commands $p]] || [llength [info commands TEI_body_$p]]} { continue }
    rename $p TEI_body_$p
    proc $p {args} [format {return [TEI_call TEI_body_%s {*}$args]} $p]
  }
}

# end of file
