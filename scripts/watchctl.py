#!/usr/bin/env python3
"""Call the locally authenticated notes API without putting its token in arguments."""
import argparse
import json
import os
from pathlib import Path
import urllib.error
import urllib.request

import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'plugin/server'))
from watch_client import call,connection

def main():
    p=argparse.ArgumentParser();p.add_argument('operation',choices=['status','notes','connect','disconnect','reminders','sync','create','update','delete','export']);p.add_argument('--id');p.add_argument('--title');p.add_argument('--body');p.add_argument('--watch-title');p.add_argument('--done',choices=['true','false']);p.add_argument('--send-to-watch',choices=['true','false']);args=p.parse_args()
    simple={'status':('status','GET'),'notes':('notes','GET'),'export':('export','GET'),'connect':('watch/connect','POST'),'disconnect':('watch/disconnect','POST'),'reminders':('watch/reminders','GET'),'sync':('watch/sync','POST')}
    if args.operation in simple:
        route,method=simple[args.operation];data={} if method=='POST' else None
    else:
        if args.operation!='create' and not args.id:p.error('--id is required')
        route='notes'+('/'+args.id if args.id else '');method={'create':'POST','update':'PATCH','delete':'DELETE'}[args.operation]
        data={k:v for k,v in [('title',args.title),('body',args.body),('watch_title',args.watch_title)] if v is not None}
        if args.done is not None:data['done']=args.done=='true'
        if args.send_to_watch is not None:data['watch_enabled']=args.send_to_watch=='true'
    print(json.dumps(call(route,method,data),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
