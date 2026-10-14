from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

OUT = Path(__file__).parent
S = 2
im = Image.new('RGB', (1560*S, 1170*S), '#EAECE7')
d = ImageDraw.Draw(im)
BG='#F8F9F5'; INK='#243D35'; MUTED='#69766F'; GREEN='#285848'; LINE='#E4E8E1'; WHITE='#FFFFFF'
def font(size,b=False): return ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if b else 'C:/Windows/Fonts/msyh.ttc',int(size*S))
def text(x,y,t,size=16,c=INK,b=False): d.text((x*S,y*S),t,font=font(size,b),fill=c)
def box(x,y,w,h,c,r=16,outline=None): d.rounded_rectangle((x*S,y*S,(x+w)*S,(y+h)*S),radius=r*S,fill=c,outline=outline,width=S)
def line(x,y,x2,y2,c=LINE,w=1): d.line((x*S,y*S,x2*S,y2*S),fill=c,width=w*S)
def circle(x,y,r,c): d.ellipse(((x-r)*S,(y-r)*S,(x+r)*S,(y+r)*S),fill=c)
def pill(x,y,t,bg='#E9EEE7',fg=GREEN):
    w=d.textlength(t,font=font(12))/S+22
    box(x,y,w,27,bg,13); text(x+11,y+5,t,12,fg); return w
def icon(cx,cy,kind,c=GREEN):
    if kind=='inbox':
        d.rounded_rectangle(((cx-10)*S,(cy-8)*S,(cx+10)*S,(cy+8)*S),radius=3*S,outline=c,width=2*S)
        line(cx-10,cy,cx-4,cy,c,2); line(cx+4,cy,cx+10,cy,c,2); line(cx-4,cy,cx-2,cy+4,c,2); line(cx-2,cy+4,cx+2,cy+4,c,2); line(cx+2,cy+4,cx+4,cy,c,2)
    elif kind=='filter':
        for dy,ww in [(-7,10),(0,7),(7,3)]: line(cx-ww,cy+dy,cx+ww,cy+dy,c,2)
    else:
        for dy in [-7,0,7]:
            line(cx-10,cy+dy,cx+10,cy+dy,c,2)
            circle(cx+(4 if dy!=0 else -4),cy+dy,3,c)
def device(x,title,sub,tab):
    box(x-3,211,428,834,'#D6DCD3',39)
    box(x,204,422,834,BG,36, '#D4DBD1')
    text(x+25,224,'9:41',13,INK,True)
    for i in range(4): box(x+338+i*5,238-i*3,3,4+i*3,INK,1)
    box(x+368,227,25,12,INK,3); box(x+371,230,18,6,BG,1)
    text(x+26,276,title,32,INK,True); text(x+27,324,sub,13,MUTED)
    line(x+1,948,x+421,948)
    for n,(label,kind) in enumerate([('收件箱','inbox'),('已过滤','filter'),('设置','settings')]):
        cx=x+72+n*139
        if n==tab: box(cx-30,960,60,30,'#DFE9DD',15)
        icon(cx,975,kind,GREEN if n==tab else MUTED)
        text(cx-20,997,label,13,GREEN if n==tab else MUTED,n==tab)
    box(x+150,1026,122,4,INK,2)
def app(x,y,name,label,time,fg='#547463',bg='#EBF0E9'):
    box(x,y,28,28,bg,8); text(x+7,y+4,name,13,fg,True)
    text(x+38,y+5,label,12,MUTED); text(x+279,y+5,time,11,MUTED)

text(66,40,'轻序',38,INK,True)
text(169,57,'QINGXU  /  ANDROID',14,MUTED)
text(66,103,'把注意力，留给值得的事。',23,INK)
pill(1230,58,'视觉概念 · 非运行截图','#DDE5DA')
text(66,163,'01  收件箱 / 先看需要关注的',14,MUTED)
text(570,163,'02  已过滤 / 每次判断都可恢复',14,MUTED)
text(1074,163,'03  设置 / 从两个应用开始',14,MUTED)

x=66; device(x,'先看重要的','10 月 14 日，星期三',0)
box(x+26,362,370,67,'#EAF0E6',18)
text(x+43,375,'2 条需要及时关注',17,GREEN,True)
text(x+43,402,'今天已收纳 12 条通知',12,MUTED)
pill(x+26,448,'全部  8',GREEN,'#FFFFFF'); pill(x+111,448,'重要'); pill(x+180,448,'未读')
text(x+27,495,'紧急且重要',13,INK,True); text(x+352,495,'01',12,MUTED)
box(x+26,525,370,164,WHITE,19,LINE)
box(x+26,542,4,48,'#B47142',2)
app(x+42,541,'Q','QQ · 项目协作群','10:24')
text(x+42,582,'今天 18:00 前确认交付清单',18,INK,True)
text(x+42,615,'请核对负责的项目，确认后在群内回复。',13,MUTED)
pill(x+42,649,'今天截止','#FAEEDC','#97642D')
text(x+27,711,'紧急 · 可以快速处理',13,INK,True)
box(x+26,741,370,112,WHITE,19,LINE)
app(x+42,756,'送','配送通知','10:18','#7B672F','#F4EEDB')
text(x+42,798,'配送员已到楼下',18,INK,True)
text(x+42,827,'请留意电话，及时领取你的订单。',13,MUTED)
text(x+27,876,'重要 · 留时间安排',13,INK,True)
text(x+27,908,'下周二体检，记得提前准备材料',14,MUTED)

x=570; device(x,'少一些打扰','收起的通知，都可以在这里找回。',1)
box(x+26,362,370,95,'#EAF0E6',18)
text(x+44,374,'12',34,GREEN,True); text(x+102,394,'条通知已收纳',14,GREEN)
text(x+44,425,'仅在轻序内收纳 · 原通知保持不变',12,MUTED)
text(x+27,483,'今天',14,INK,True); text(x+295,484,'按接收时间',12,MUTED)
for yy,letter,label,title,body,time in [
    (516,'购','购物应用','秋日上新，限时领取优惠券','你可能感兴趣的商品正在优惠中。','10:12'),
    (691,'旅','旅行应用','周末出发，精选目的地推荐','发现下一段旅程，查看本周特惠。','09:46')]:
    box(x+26,yy,370,155,WHITE,19,LINE)
    app(x+42,yy+14,letter,label,time,'#7D7971','#F0EFEB')
    text(x+42,yy+54,title,16,INK,True)
    text(x+42,yy+84,body,12,MUTED)
    pill(x+42,yy+115,'促销推广','#F1F2ED',MUTED)
    text(x+288,yy+120,'恢复到收件箱',12,GREEN,True)
text(x+40,880,'判断不合适？恢复后，你也可以标记原因。',12,MUTED)

x=1074; device(x,'按你的习惯','选择接收什么，也决定如何整理。',2)
box(x+26,362,370,82,'#EAF0E6',18)
circle(x+49,389,4,'#497B50'); text(x+63,376,'通知访问已开启',16,GREEN,True)
text(x+43,409,'当前接收所选应用的通知',12,MUTED)
text(x+27,473,'接收的应用',14,INK,True); text(x+325,474,'管理 ›',13,GREEN)
box(x+26,506,370,186,WHITE,19,LINE)
for i,(letter,name,fg,bg) in enumerate([('Q','QQ','#537466','#EAF0E6'),('送','配送应用','#7B672F','#F4EEDB'),('购','购物应用','#7D7971','#F0EFEB')]):
    yy=521+i*58
    box(x+42,yy,32,32,bg,9); text(x+51,yy+5,letter,14,fg,True); text(x+89,yy+5,name,15,INK)
    box(x+329,yy+3,44,25,GREEN,13); circle(x+360,yy+15,9,WHITE)
    if i<2: line(x+88,yy+44,x+377,yy+44)
text(x+27,720,'整理方式',14,INK,True)
box(x+26,752,370,127,WHITE,19,LINE)
text(x+43,769,'自动移除原通知',15,INK)
text(x+43,797,'关闭时，仅在轻序内分类收纳',12,MUTED)
box(x+329,771,44,25,'#DDE2D9',13); circle(x+342,783,9,WHITE)
line(x+43,830,x+377,830)
text(x+43,846,'通知保存时间',14,INK); text(x+307,846,'7 天  ›',13,MUTED)
text(x+39,905,'你的消息，由你决定。',12,MUTED)

text(66,1081,'设计方向 A · 安静的收件箱',19,INK,True)
text(66,1118,'暖白底 / 深墨绿 / 仅关键时间使用琥珀色 / 原生底部导航 / 少边框、少装饰',14,MUTED)
for i,(color,label) in enumerate([(BG,'背景'),(GREEN,'主色'),('#B47142','时间'),(INK,'文字')]):
    cx=1165+i*88; circle(cx,1099,15,color); text(cx-13,1125,label,12,MUTED)
im.save(OUT/'qingxu-concept.png')
print(OUT/'qingxu-concept.png')
