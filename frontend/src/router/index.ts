import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import { ERouterName } from '/@/types/index'
import CreatePlan from '/@/components/task/CreatePlan.vue'
import WaylinePanel from '/@/pages/page-web/projects/wayline.vue'
import DockPanel from '/@/pages/page-web/projects/dock.vue'
import LiveOthers from '/@/components/livestream-others.vue'

const routes: Array<RouteRecordRaw> = [
  {
    path: '/',
    redirect: '/' + ERouterName.PROJECT
  },
  // 首页
  {
    path: '/' + ERouterName.PROJECT,
    name: ERouterName.PROJECT,
    component: () => import('/@/pages/page-web/index.vue')
  },
  // members, devices
  {
    path: '/' + ERouterName.HOME,
    name: ERouterName.HOME,
    component: () => import('/@/pages/page-web/home.vue'),
    children: [
      {
        path: '/' + ERouterName.LEADERSHIP_COCKPIT,
        name: ERouterName.LEADERSHIP_COCKPIT,
        component: () => import('/@/pages/page-web/projects/leadership-cockpit.vue')
      },
      {
        path: '/' + ERouterName.MEMBERS,
        name: ERouterName.MEMBERS,
        component: () => import('/@/pages/page-web/projects/members.vue')
      },
      {
        path: '/' + ERouterName.DEVICES,
        name: ERouterName.DEVICES,
        component: () => import('/@/pages/page-web/projects/devices.vue')
      },
      {
        path: '/' + ERouterName.FIRMWARES,
        name: ERouterName.FIRMWARES,
        component: () => import('../pages/page-web/projects/Firmwares.vue')
      }
    ]
  },
  // workspace
  {
    path: '/' + ERouterName.WORKSPACE,
    name: ERouterName.WORKSPACE,
    component: () => import('/@/pages/page-web/projects/workspace.vue'),
    redirect: '/' + ERouterName.TSA,
    children: [
      {
        path: '/' + ERouterName.TSA,
        component: () => import('/@/pages/page-web/projects/tsa.vue')
      },
      {
        path: '/' + ERouterName.LIVESTREAM,
        name: ERouterName.LIVESTREAM,
        component: () => import('/@/pages/page-web/projects/livestream.vue'),
        children: [
          {
            path: ERouterName.LIVING,
            name: ERouterName.LIVING,
            components: {
              LiveOthers
            }
          }
        ]
      },
      {
        path: '/' + ERouterName.LAYER,
        name: ERouterName.LAYER,
        component: () => import('/@/pages/page-web/projects/layer.vue')
      },
      {
        path: '/' + ERouterName.MEDIA,
        name: ERouterName.MEDIA,
        component: () => import('/@/pages/page-web/projects/media.vue')
      },
      {
        path: '/' + ERouterName.WAYLINE,
        name: ERouterName.WAYLINE,
        component: () => import('/@/pages/page-web/projects/wayline.vue')
      },
      {
        path: '/' + ERouterName.TASK,
        name: ERouterName.TASK,
        component: () => import('/@/pages/page-web/projects/task.vue'),
        children: [
          {
            path: ERouterName.CREATE_PLAN,
            name: ERouterName.CREATE_PLAN,
            component: CreatePlan,
            children: [
              {
                path: ERouterName.SELECT_PLAN,
                name: ERouterName.SELECT_PLAN,
                components: {
                  WaylinePanel,
                  DockPanel
                }
              }
            ]
          }

        ]
      },
      {
        path: '/' + ERouterName.FLIGHT_AREA,
        name: ERouterName.FLIGHT_AREA,
        component: () => import('/@/pages/page-web/projects/flight-area.vue')
      },
      // fc100 灭火模块(挂在 workspace 子路由下,保留左侧 sidebar)
      {
        path: '/' + ERouterName.FIRE_EVENTS,
        name: ERouterName.FIRE_EVENTS,
        component: () => import('/@/pages/page-web/projects/fire/FireEventList.vue')
      },
      {
        path: '/' + ERouterName.FIRE_MISSIONS,
        name: ERouterName.FIRE_MISSIONS,
        component: () => import('/@/pages/page-web/projects/fire/FireMissionList.vue')
      },
      {
        path: '/' + ERouterName.FIRE_MISSION_DETAIL + '/:no',
        name: ERouterName.FIRE_MISSION_DETAIL,
        component: () => import('/@/pages/page-web/projects/fire/FireMissionDetail.vue'),
        props: true
      },
      {
        path: '/' + ERouterName.FIRE_ROUTE_PREVIEW + '/:no',
        name: ERouterName.FIRE_ROUTE_PREVIEW,
        component: () => import('/@/pages/page-web/projects/fire/FireRoutePreview.vue'),
        props: true
      },
      {
        path: '/' + ERouterName.FIRE_PAYLOAD_RELEASE + '/:no',
        name: ERouterName.FIRE_PAYLOAD_RELEASE,
        component: () => import('/@/pages/page-web/projects/fire/FirePayloadRelease.vue'),
        props: true
      },
    ]
  },
  // pilot
  {
    path: '/' + ERouterName.PILOT,
    name: ERouterName.PILOT,
    component: () => import('/@/pages/page-pilot/pilot-index.vue'),
  },
  {
    path: '/' + ERouterName.PILOT_HOME,
    component: () => import('/@/pages/page-pilot/pilot-home.vue')
  },
  {
    path: '/' + ERouterName.PILOT_MEDIA,
    component: () => import('/@/pages/page-pilot/pilot-media.vue')
  },
  {
    path: '/' + ERouterName.PILOT_LIVESHARE,
    component: () => import('/@/pages/page-pilot/pilot-liveshare.vue')
  },
  {
    path: '/' + ERouterName.PILOT_BIND,
    component: () => import('/@/pages/page-pilot/pilot-bind.vue')
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
