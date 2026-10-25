#!/usr/bin/env python3
"""Connect the private USB API, optionally take over or release the watch."""
import argparse
from pathlib import Path
import sys
import webbrowser
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'plugin/server'))
from phone import connect_usb,take_over,release

def main():
    p=argparse.ArgumentParser();p.add_argument('--open',action='store_true');group=p.add_mutually_exclusive_group();group.add_argument('--watch',action='store_true');group.add_argument('--release',action='store_true');args=p.parse_args()
    if args.release:print(release());return
    token=connect_usb()
    if args.watch:print(take_over())
    if args.open:webbrowser.open('http://127.0.0.1:8766/#token='+token)
    print('PC notes: http://127.0.0.1:8766. Access credential saved privately.')

if __name__=='__main__':main()
