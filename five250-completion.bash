# Bash tab-completion for the five250 CLI.
#
# Setup:
#   1. Put five250/bin on PATH (or symlink bin/five250 into a directory that already is):
#        export PATH="$PATH:/c/acabes/as400/five250/bin"
#   2. Source this file (add the line to ~/.bashrc to make it permanent):
#        source /c/acabes/as400/five250/five250-completion.bash
#
# Works in plain bash — does not require the `bash-completion` package.

_five250_complete() {
  local cur cword subcmd
  cword=$COMP_CWORD
  cur="${COMP_WORDS[COMP_CWORD]}"

  local commands="connect signon screen fields type key disconnect shutdown help daemon run-suite"
  local keys="ENTER PF1 PF2 PF3 PF4 PF5 PF6 PF7 PF8 PF9 PF10 PF11 PF12 PF13 PF14 PF15 PF16 PF17 PF18 PF19 PF20 PF21 PF22 PF23 PF24 F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12 F13 F14 F15 F16 F17 F18 F19 F20 F21 F22 F23 F24 PA1 PA2 PA3 PAGE_UP PAGE_DOWN PAGEUP PAGEDOWN ROLL_UP ROLL_DOWN TAB BACK_TAB HOME END_OF_FIELD ERASE_EOF ERASE_FIELD CLEAR HELP SYSREQ ATTN"

  if [[ $cword -eq 1 ]]; then
    COMPREPLY=( $(compgen -W "$commands" -- "$cur") )
    return 0
  fi

  subcmd="${COMP_WORDS[1]}"

  if [[ "$subcmd" == "key" ]]; then
    if [[ $cword -eq 2 ]]; then
      COMPREPLY=( $(compgen -W "$keys" -- "${cur^^}") )
    else
      COMPREPLY=( $(compgen -W "--session" -- "$cur") )
    fi
    return 0
  fi

  if [[ "$subcmd" == "help" ]]; then
    if [[ $cword -eq 2 ]]; then
      COMPREPLY=( $(compgen -W "$commands" -- "$cur") )
    fi
    return 0
  fi

  local opts=""
  case "$subcmd" in
    connect)    opts="--host --port --ssl --session" ;;
    signon)     opts="--user --pass --session" ;;
    screen)     opts="--json --session" ;;
    fields)     opts="--json --session" ;;
    type)       opts="--label --row --col --value --session" ;;
    disconnect) opts="--session" ;;
    run-suite)  opts="--flow --file --session --var --timeout" ;;
  esac
  COMPREPLY=( $(compgen -W "$opts" -- "$cur") )
}

complete -F _five250_complete five250
